import Foundation
import UIKit
import QuartzCore
import OpenGLES
import Libmpv
import Darwin

/// iOS 15 in-app libmpv rendering through the supported OpenGL ES render API.
///
/// Avoids both the Vulkan/MoltenVK CAMetalLayer path (crashes on the reported
/// iOS 15.3 device) and the CPU-based SW renderer (432p/24fps).
///
/// OpenGL ES has been deprecated by Apple, but remains available on iOS 15.
/// The libmpv binary must have its OpenGL render backend compiled in; if
/// context creation fails, MPVPlayerViewController uses its safe SW fallback.
final class MPVLegacyGLRenderer: UIView {
    override class var layerClass: AnyClass { CAEAGLLayer.self }

    private let glContext: EAGLContext?
    private let renderQueue = DispatchQueue(label: "nuvio.ios15.mpv.gl", qos: .userInteractive)
    private var mpvRenderContext: OpaquePointer?
    private var framebuffer: GLuint = 0
    private var colorbuffer: GLuint = 0
    private var pixelWidth: GLint = 0
    private var pixelHeight: GLint = 0
    private var destroyed = false
    private var needsRedraw = true

    override init(frame: CGRect) {
        glContext = EAGLContext(api: .openGLES3) ?? EAGLContext(api: .openGLES2)
        super.init(frame: frame)
        contentScaleFactor = UIScreen.main.nativeScale
        isOpaque = true
        let drawable = layer as! CAEAGLLayer
        drawable.isOpaque = true
        drawable.drawableProperties = [
            kEAGLDrawablePropertyRetainedBacking: false,
            kEAGLDrawablePropertyColorFormat: kEAGLColorFormatRGBA8
        ]
    }

    required init?(coder: NSCoder) { return nil }

    /// Attach only *after* mpv_initialize; do not start video playback until
    /// the render context exists. Returns an error instead of calling fatalError.
    func start(mpv: OpaquePointer) -> String? {
        var error: String?
        renderQueue.sync {
            guard !destroyed else { error = "Renderer already stopped"; return }
            guard let glContext else {
                error = "This device has no available OpenGL ES context."
                return
            }
            EAGLContext.setCurrent(glContext)

            var glInit = mpv_opengl_init_params(
                get_proc_address: { _, symbol in
                    guard let symbol else { return nil }
                    return dlsym(UnsafeMutableRawPointer(bitPattern: -2), symbol)
                },
                get_proc_address_ctx: nil
            )
            let status: CInt = "opengl".withCString { type in
                withUnsafeMutablePointer(to: &glInit) { initPointer in
                    var arguments = [
                        mpv_render_param(
                            type: MPV_RENDER_PARAM_API_TYPE,
                            data: UnsafeMutableRawPointer(mutating: type)
                        ),
                        mpv_render_param(
                            type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,
                            data: UnsafeMutableRawPointer(initPointer)
                        ),
                        mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil)
                    ]
                    var renderer: OpaquePointer?
                    let result = mpv_render_context_create(&renderer, mpv, &arguments)
                    if result >= 0 { mpvRenderContext = renderer }
                    return result
                }
            }
            guard status >= 0, let renderer = mpvRenderContext else {
                error = "OpenGL render API unavailable: " +
                    String(cString: mpv_error_string(status))
                EAGLContext.setCurrent(nil)
                return
            }

            mpv_render_context_set_update_callback(
                renderer,
                { userData in
                    guard let userData else { return }
                    let view = Unmanaged<MPVLegacyGLRenderer>
                        .fromOpaque(userData).takeUnretainedValue()
                    view.renderQueue.async { [weak view] in
                        view?.needsRedraw = true
                        view?.draw()
                    }
                },
                Unmanaged.passUnretained(self).toOpaque()
            )
            EAGLContext.setCurrent(nil)
        }

        if error == nil {
            setNeedsLayout()
            layoutIfNeeded()
        }
        return error
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // UIKit geometry is read only on the main thread, then all GL API
        // calls are executed on a dedicated serial renderer queue.
        renderQueue.async { [weak self] in
            self?.needsRedraw = true
            self?.recreateFramebuffer()
            self?.draw()
        }
    }

    private func recreateFramebuffer() {
        guard !destroyed, let glContext else { return }
        EAGLContext.setCurrent(glContext)
        releaseFramebuffer()

        glGenFramebuffers(1, &framebuffer)
        glGenRenderbuffers(1, &colorbuffer)
        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        glBindRenderbuffer(GLenum(GL_RENDERBUFFER), colorbuffer)

        guard glContext.renderbufferStorage(
            Int(GL_RENDERBUFFER), from: layer as! CAEAGLLayer
        ) else {
            releaseFramebuffer()
            return
        }

        glFramebufferRenderbuffer(
            GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0),
            GLenum(GL_RENDERBUFFER), colorbuffer
        )
        guard glCheckFramebufferStatus(GLenum(GL_FRAMEBUFFER)) ==
            GLenum(GL_FRAMEBUFFER_COMPLETE) else {
            releaseFramebuffer()
            return
        }

        glGetRenderbufferParameteriv(
            GLenum(GL_RENDERBUFFER), GLenum(GL_RENDERBUFFER_WIDTH), &pixelWidth
        )
        glGetRenderbufferParameteriv(
            GLenum(GL_RENDERBUFFER), GLenum(GL_RENDERBUFFER_HEIGHT), &pixelHeight
        )
    }

    private func draw() {
        guard !destroyed, let renderer = mpvRenderContext, let glContext else { return }
        EAGLContext.setCurrent(glContext)
        _ = mpv_render_context_update(renderer)
        guard framebuffer != 0, pixelWidth > 0, pixelHeight > 0, needsRedraw else { return }
        needsRedraw = false

        glBindFramebuffer(GLenum(GL_FRAMEBUFFER), framebuffer)
        glViewport(0, 0, pixelWidth, pixelHeight)

        var fbo = mpv_opengl_fbo(
            fbo: Int32(framebuffer),
            w: pixelWidth,
            h: pixelHeight,
            internal_format: 0
        )
        let result = withUnsafeMutablePointer(to: &fbo) { fboPointer in
            var args = [
                mpv_render_param(
                    type: MPV_RENDER_PARAM_OPENGL_FBO,
                    data: UnsafeMutableRawPointer(fboPointer)
                ),
                mpv_render_param(type: MPV_RENDER_PARAM_INVALID, data: nil)
            ]
            return mpv_render_context_render(renderer, &args)
        }
        if result >= 0 {
            glBindRenderbuffer(GLenum(GL_RENDERBUFFER), colorbuffer)
            _ = glContext.presentRenderbuffer(Int(GL_RENDERBUFFER))
            mpv_render_context_report_swap(renderer)
        } else {
            NSLog("[Nuvio][MPV][OpenGLES] render error: %d", result)
        }
    }

    private func releaseFramebuffer() {
        if framebuffer != 0 {
            glDeleteFramebuffers(1, &framebuffer)
            framebuffer = 0
        }
        if colorbuffer != 0 {
            glDeleteRenderbuffers(1, &colorbuffer)
            colorbuffer = 0
        }
        pixelWidth = 0
        pixelHeight = 0
    }

    /// Frees the render context before the owning mpv core is destroyed.
    /// Also disables callbacks before making the view eligible for release.
    func stop() {
        renderQueue.sync {
            guard !destroyed else { return }
            destroyed = true
            if let glContext { EAGLContext.setCurrent(glContext) }
            if let renderer = mpvRenderContext {
                mpv_render_context_set_update_callback(renderer, nil, nil)
                mpv_render_context_free(renderer)
                mpvRenderContext = nil
            }
            releaseFramebuffer()
            if let glContext, EAGLContext.current() === glContext {
                EAGLContext.setCurrent(nil)
            }
        }
    }
}

#include <client/ovpncli.hpp>
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace {
namespace core = openvpn::ClientAPI;

// Core owns the protocol thread. Android callbacks execute only on that thread.
class Session final : public core::OpenVPNClient {
public:
    JavaVM *vm;
    jobject owner;
    jmethodID establish, protect;
    std::thread thread;
    std::atomic<long long> received{0}, sent{0}, authenticated{0};
    std::atomic<bool> ended{false}, closing{false}, authRejected{false};
    void note(const std::string &text) {
        if (text.empty()) return;
        std::lock_guard<std::mutex> lock(detailLock);
        if (detail.size() < 400) detail += (detail.empty() ? "" : " | ") + text.substr(0, 160);
    }
    std::string lastDetail() {
        std::lock_guard<std::mutex> lock(detailLock);
        return detail;
    }
    Session(JNIEnv *env, jobject instance) {
        env->GetJavaVM(&vm);
        owner = env->NewGlobalRef(instance);
        jclass type = env->GetObjectClass(instance);
        establish = env->GetMethodID(type, "establish", "([Ljava/lang/String;[Ljava/lang/String;I)I");
        protect = env->GetMethodID(type, "protectSocket", "(I)Z");
    }
    void run() {
        JNIEnv *env = nullptr;
        if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            callbackEnv = env;
            try {
                if (!closing) {
                    auto status = connect();
                    if (status.error) note("engine: " + status.status + (status.message.empty() ? "" : " " + status.message));
                }
            } catch (const std::exception &failure) { note(std::string("engine exception: ") + failure.what()); }
            catch (...) { note("engine exception"); }
            callbackEnv = nullptr;
            vm->DetachCurrentThread();
        }
        authenticated = 0;
        ended = true;
    }
    bool socket_protect(int fd, std::string, bool) override {
        bool ok = callbackEnv->CallBooleanMethod(owner, protect, fd);
        return !clearException() && ok;
    }
    bool tun_builder_new() override {
        addresses.clear(); dns.clear(); mtu = 1500; authenticated = 0; return true;
    }
    bool tun_builder_set_layer(int layer) override {
        if (layer != 3) note("profile uses layer " + std::to_string(layer) + " (dev tap), not supported");
        return layer == 3;
    }
    bool tun_builder_set_remote_address(const std::string &, bool) override { return true; }
    bool tun_builder_add_address(const std::string &ip, int prefix, const std::string &, bool, bool) override {
        if (addresses.size() >= 8) return false;
        addresses.push_back(ip + "/" + std::to_string(prefix)); return true;
    }
    // Each engine installs the app's full-route policy itself; never copy a pushed bypass.
    bool tun_builder_reroute_gw(bool, bool, unsigned int) override { return true; }
    bool tun_builder_add_route(const std::string &, int, int, bool) override { return true; }
    bool tun_builder_exclude_route(const std::string &, int, int, bool) override { return true; }
    bool tun_builder_set_allow_family(int, bool) override { return true; }
    bool tun_builder_set_session_name(const std::string &) override { return true; }
    bool tun_builder_set_mtu(int value) override {
        if (value < 576 || value > 9000) { note("server MTU " + std::to_string(value) + " refused"); return false; }
        mtu = value; return true;
    }
    bool tun_builder_set_dns_options(const openvpn::DnsOptions &options) override {
        dns.clear();
        for (const auto &[priority, server] : options.servers) {
            if (server.transport == openvpn::DnsServer::Transport::HTTPS
                || server.transport == openvpn::DnsServer::Transport::TLS
                || server.dnssec == openvpn::DnsServer::Security::Yes) { note("secure DNS (DoH/DoT/DNSSEC) pushed, not supported"); return false; }
            for (const auto &item : server.addresses) {
                if ((item.port != 0 && item.port != 53) || dns.size() >= 8) { note("DNS server on port " + std::to_string(item.port) + " or too many, refused"); return false; }
                dns.push_back(item.address);
            }
        }
        return true;
    }
    int tun_builder_establish() override {
        if (!callbackEnv || addresses.empty() || dns.empty()) {
            note(std::string("tunnel setup: ") + (addresses.empty() ? "no address pushed" : "no DNS pushed"));
            return -1;
        }
        if (callbackEnv->PushLocalFrame(32) < 0) return -1;
        auto addrArray = strings(addresses), dnsArray = strings(dns);
        int fd = addrArray && dnsArray ? callbackEnv->CallIntMethod(owner, establish, addrArray, dnsArray, mtu) : -1;
        if (clearException()) fd = -1;
        callbackEnv->PopLocalFrame(nullptr); return fd;
    }
    void tun_builder_teardown(bool) override { authenticated = 0; }
    bool tun_builder_persist() override { return false; }
    bool pause_on_connection_timeout() override { return false; }
    void event(const core::Event &e) override {
#ifndef NDEBUG
        __android_log_print(ANDROID_LOG_INFO,"NuvioVpn","openvpn_event: %s",e.name.c_str());
#endif
        if (closing) { stop(); return; }
        if (e.error || e.name == "AUTH_FAILED" || e.name == "TLS_ERROR" || e.name == "CERT_VERIFY_FAIL"
            || e.name == "DISCONNECTED" || e.name == "RECONNECTING" || e.name == "RESOLVE" && e.error
            || e.name == "TUN_SETUP_FAILED" || e.name == "TUN_IFACE_CREATE" || e.name == "CONNECTION_TIMEOUT"
            || e.name == "INACTIVE_TIMEOUT" || e.name == "PROXY_ERROR" || e.name == "CLIENT_HALT"
            || e.name == "CLIENT_RESTART" || e.name == "INFO" || e.name == "COMPRESSION_ENABLED")
            note(e.name + (e.info.empty() ? "" : ": " + e.info));
        if (e.name == "AUTH_FAILED") authRejected = true;
        if (e.name == "CONNECTED") { authenticated = ++generation; clock_tick(); }
        else if (e.error || e.name == "DISCONNECTED" || e.name == "RECONNECTING") authenticated = 0;
    }
    void clock_tick() override {
        if (closing) { stop(); return; }
        auto stats = transport_stats(); received = stats.bytesIn; sent = stats.bytesOut;
    }
    void log(const core::LogInfo &) override {}
    void acc_event(const core::AppCustomControlMessageEvent &) override {}
    void external_pki_cert_request(core::ExternalPKICertRequest &r) override { r.error = true; }
    void external_pki_sign_request(core::ExternalPKISignRequest &r) override { r.error = true; }
private:
    JNIEnv *callbackEnv = nullptr;
    std::vector<std::string> addresses, dns;
    std::mutex detailLock;
    std::string detail;
    int mtu = 1500;
    long long generation = 0;
    bool clearException() {
        if (!callbackEnv->ExceptionCheck()) return false;
        callbackEnv->ExceptionClear(); return true;
    }
    jobjectArray strings(const std::vector<std::string> &values) {
        auto out = callbackEnv->NewObjectArray(values.size(), callbackEnv->FindClass("java/lang/String"), nullptr);
        if (!out) return nullptr;
        for (size_t i = 0; i < values.size(); ++i) {
            auto s = callbackEnv->NewStringUTF(values[i].c_str());
            if (!s) return nullptr;
            callbackEnv->SetObjectArrayElement(out, i, s);
            callbackEnv->DeleteLocalRef(s);
        }
        return out;
    }
};

bool load(JNIEnv *env, jbyteArray data, jboolean noCert, jboolean aesCbc, core::Config &cfg) {
    if (!data) return false;
    auto size = env->GetArrayLength(data);
    if (size <= 0 || size > 1024 * 1024) return false;
    cfg.content.resize(size);
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(cfg.content.data()));
    cfg.content += "\nremote-cert-tls server\n";
    cfg.disableClientCert = noCert;
    cfg.enableNonPreferredDCAlgorithms = aesCbc;
    cfg.compressionMode = "asym"; // accept the server's compression setting but never compress what we send
    cfg.connTimeout = 25;
    cfg.clockTickMS = 500;
    cfg.tunPersist = false;
    cfg.enableRouteEmulation = false;
    return !env->ExceptionCheck();
}
} // namespace

extern "C" JNIEXPORT jint JNICALL Java_com_nuvio_vpn_android_OpenVpnSession_evaluateNative(
        JNIEnv *env, jclass, jbyteArray text, jboolean noCert, jboolean aesCbc) {
    try {
        core::Config cfg;
        if (!load(env, text, noCert, aesCbc, cfg)) return 0;
        auto e = core::OpenVPNClientHelper().eval_config(cfg);
        if (e.error || e.vpnCa.empty() || e.privateKeyPasswordRequired || !e.staticChallenge.empty()) return 0;
        return e.autologin ? 1 : 2;
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT jlong JNICALL Java_com_nuvio_vpn_android_OpenVpnSession_startNative(
        JNIEnv *env, jobject owner, jbyteArray text, jboolean noCert, jboolean aesCbc) {
    try {
        core::Config cfg;
        if (!load(env, text, noCert, aesCbc, cfg)) return 0;
        auto session = std::make_unique<Session>(env, owner);
        auto e = session->eval_config(cfg);
        if (!session->owner || env->ExceptionCheck() || e.error || e.vpnCa.empty() || !e.autologin) {
            if (session->owner) env->DeleteGlobalRef(session->owner);
            return 0;
        }
        try { session->thread = std::thread(&Session::run, session.get()); }
        catch (...) { env->DeleteGlobalRef(session->owner); throw; }
        return reinterpret_cast<jlong>(session.release());
    } catch (...) { return 0; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_nuvio_vpn_android_OpenVpnSession_statsNative(
        JNIEnv *env, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    jlong values[] = {s->received.load(), s->sent.load(), s->authRejected ? -2LL : s->ended ? -1LL : s->authenticated.load()};
    auto out = env->NewLongArray(3);
    if (out) env->SetLongArrayRegion(out,0,3,values);
    return out;
}
extern "C" JNIEXPORT void JNICALL Java_com_nuvio_vpn_android_OpenVpnSession_stopNative(
        JNIEnv *env, jclass, jlong handle) {
    std::unique_ptr<Session> s(reinterpret_cast<Session *>(handle));
    s->closing = true;
    s->stop();
    s->thread.join();
    env->DeleteGlobalRef(s->owner);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_nuvio_vpn_android_OpenVpnSession_detailNative(
        JNIEnv *env, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    return env->NewStringUTF(s->lastDetail().c_str());
}

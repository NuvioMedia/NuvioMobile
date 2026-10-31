#!/usr/bin/env python3
"""Exercise upstream gl_poll_callbacks with fake GL, without requiring a GPU.

Usage: python3 scripts/test-libplacebo-sync.py path/to/libplacebo/src/opengl/utils.c
Requires a host C compiler (CC, defaults to cc).
"""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

source = Path(sys.argv[1]).read_text(encoding="utf-8")
start = source.index("void gl_poll_callbacks(pl_gpu gpu)")
end = source.index("\nbool gl_check_err", start)
function = source[start:end]
prefix = r"""
#include <assert.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
typedef unsigned GLenum;
enum { GL_ALREADY_SIGNALED, GL_CONDITION_SATISFIED, GL_WAIT_FAILED, GL_TIMEOUT_EXPIRED };
struct gl_cb { void *sync; void (*callback)(void *); void *priv; };
typedef struct { GLenum (*ClientWaitSync)(void *, unsigned, unsigned long long);
                 void (*DeleteSync)(void *); } gl_funcs;
struct pl_gl { struct { struct gl_cb *elem; int num; } callbacks; bool failed; };
typedef struct test_gpu { gl_funcs gl; struct pl_gl p; } *pl_gpu;
static int deleted, called, errors;
static void *last_deleted;
static GLenum result;
static const gl_funcs *gl_funcs_get(pl_gpu gpu) { return &gpu->gl; }
#define PL_PRIV(gpu) (&(gpu)->p)
#define PL_ARRAY_REMOVE_AT(a, i) do { \
    memmove((a).elem + (i), (a).elem + (i) + 1, \
            (--(a).num - (i)) * sizeof(*(a).elem)); \
} while (0)
#define pl_unreachable() abort()
static bool gl_check_err(pl_gpu gpu, const char *fun) {
    (void)gpu; (void)fun; errors++; return false;
}
static GLenum wait_sync(void *sync, unsigned flags, unsigned long long timeout) {
    (void)sync; assert(flags == 0 && timeout == 0); return result;
}
static void delete_sync(void *sync) { deleted++; last_deleted = sync; }
static void callback(void *priv) {
    assert(deleted == called + 1); /* Delete completed fence before callback. */
    assert(last_deleted == priv);
    called++;
}
"""
suffix = r"""
int main(void) {
    int ids[3];
    for (int status = GL_ALREADY_SIGNALED; status <= GL_TIMEOUT_EXPIRED; status++) {
        struct gl_cb callbacks[3];
        for (int i = 0; i < 3; i++)
            callbacks[i] = (struct gl_cb){&ids[i], callback, &ids[i]};
        struct test_gpu gpu = {{wait_sync, delete_sync}, {{callbacks, 3}, false}};
        deleted = called = errors = 0;
        last_deleted = NULL;
        result = status;
        gl_poll_callbacks(&gpu);
        if (status == GL_TIMEOUT_EXPIRED) {
            assert(gpu.p.callbacks.num == 3 && deleted == 0 && called == 0);
            assert(!gpu.p.failed && errors == 0);
        } else if (status == GL_WAIT_FAILED) {
            assert(gpu.p.callbacks.num == 2 && deleted == 1 && called == 0);
            assert(gpu.p.failed && errors == 1);
        } else {
            assert(gpu.p.callbacks.num == 0 && deleted == 3 && called == 3);
            assert(!gpu.p.failed && errors == 0);
            gl_poll_callbacks(&gpu);
            assert(deleted == 3 && called == 3); /* No double destruction. */
        }
    }
    puts("PASS: signaled/satisfied fences, callback ordering, timeout, failure, empty queue");
}
"""
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    test = root / "sync.c"
    binary = root / "sync-test"
    test.write_text(prefix + function + suffix, encoding="utf-8")
    subprocess.run([*shlex.split(os.environ.get("CC", "cc")), "-std=c11", "-Wall", "-Wextra",
                    "-Werror", str(test), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)

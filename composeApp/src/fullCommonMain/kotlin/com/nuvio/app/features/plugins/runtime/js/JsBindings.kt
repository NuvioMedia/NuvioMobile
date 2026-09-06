package com.nuvio.app.features.plugins.runtime.js

internal object JsBindings {
    fun buildPolyfillCode(scraperIdJson: String, settingsJson: String): String {
        return """
            globalThis.SCRAPER_ID = $scraperIdJson;
            globalThis.SCRAPER_SETTINGS = $settingsJson;
            if (typeof globalThis.global === 'undefined') globalThis.global = globalThis;
            if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;
            if (typeof globalThis.self === 'undefined') globalThis.self = globalThis;

            ${fetchPolyfill()}
            ${abortControllerPolyfill()}
            ${base64Polyfill()}
            ${urlPolyfill()}
            ${cryptoPolyfill()}
            ${textEncoderPolyfill()}
            ${cheerioPolyfill()}
            ${requirePolyfill()}
            ${arrayPolyfill()}
            ${objectPolyfill()}
            ${stringPolyfill()}
        """.trimIndent()
    }

    private fun fetchPolyfill() = """
        function __normalize_fetch_headers(headers) {
            var out = {};
            if (!headers) return out;
            if (typeof headers.forEach === 'function') {
                headers.forEach(function(value, key) { out[key] = String(value); });
                return out;
            }
            if (Array.isArray(headers)) {
                headers.forEach(function(pair) {
                    if (pair && pair.length >= 2) out[pair[0]] = String(pair[1]);
                });
                return out;
            }
            Object.keys(headers).forEach(function(key) { out[key] = String(headers[key]); });
            return out;
        }

        var fetch = async function(url, options) {
            options = options || {};
            var method = (options.method || 'GET').toUpperCase();
            var headers = __normalize_fetch_headers(options.headers);
            var body = options.body || '';
            var followRedirects = options.redirect !== 'manual';
            var useCfKiller = options.cfKiller === true;
            var result = __native_fetch(url, method, JSON.stringify(headers), body, followRedirects, useCfKiller);
            var parsed = JSON.parse(result);
            return {
                ok: parsed.ok,
                status: parsed.status,
                statusText: parsed.statusText,
                url: parsed.url,
                headers: {
                    get: function(name) {
                        return parsed.headers[name.toLowerCase()] || null;
                    }
                },
                text: function() { return Promise.resolve(parsed.body); },
                json: function() {
                    try {
                        if (parsed.body === null || parsed.body === undefined || parsed.body === '') {
                            return Promise.resolve(null);
                        }
                        return Promise.resolve(JSON.parse(parsed.body));
                    } catch (e) {
                        return Promise.resolve(null);
                    }
                }
            };
        };
    """.trimIndent()

    private fun abortControllerPolyfill() = """
        if (typeof AbortSignal === 'undefined') {
            var AbortSignal = function() { this.aborted = false; this.reason = undefined; this._listeners = []; };
            AbortSignal.prototype.addEventListener = function(type, listener) {
                if (type !== 'abort' || typeof listener !== 'function') return;
                this._listeners.push(listener);
            };
            AbortSignal.prototype.removeEventListener = function(type, listener) {
                if (type !== 'abort') return;
                this._listeners = this._listeners.filter(function(l) { return l !== listener; });
            };
            AbortSignal.prototype.dispatchEvent = function(event) {
                if (!event || event.type !== 'abort') return true;
                for (var i = 0; i < this._listeners.length; i++) {
                    try { this._listeners[i].call(this, event); } catch (e) {}
                }
                return true;
            };
            globalThis.AbortSignal = AbortSignal;
        }

        if (typeof AbortController === 'undefined') {
            var AbortController = function() { this.signal = new AbortSignal(); };
            AbortController.prototype.abort = function(reason) {
                if (this.signal.aborted) return;
                this.signal.aborted = true;
                this.signal.reason = reason;
                this.signal.dispatchEvent({ type: 'abort' });
            };
            globalThis.AbortController = AbortController;
        }
    """.trimIndent()

    private fun base64Polyfill() = """
        if (typeof atob === 'undefined') {
            globalThis.atob = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input).replace(/=+$/, '');
                if (str.length % 4 === 1) throw new Error('InvalidCharacterError');
                var output = '';
                var bc = 0, bs, buffer, idx = 0;
                while ((buffer = str.charAt(idx++))) {
                    buffer = chars.indexOf(buffer);
                    if (buffer === -1) continue;
                    bs = bc % 4 ? bs * 64 + buffer : buffer;
                    if (bc++ % 4) output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
                }
                return output;
            };
        }

        if (typeof btoa === 'undefined') {
            globalThis.btoa = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input);
                var output = '';
                for (var block, charCode, idx = 0, map = chars;
                     str.charAt(idx | 0) || (map = '=', idx % 1);
                     output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))) {
                    charCode = str.charCodeAt(idx += 3 / 4);
                    if (charCode > 0xFF) throw new Error('InvalidCharacterError');
                    block = (block << 8) | charCode;
                }
                return output;
            };
        }
    """.trimIndent()

    private fun urlPolyfill() = """
        var __native_parse_url = typeof __parse_url !== 'undefined' ? __parse_url : function(u) { return JSON.stringify({ protocol: '', host: '', hostname: '', port: '', pathname: '/', search: '', hash: '' }); };
        var URL = function(urlString, base) {
            var fullUrl = urlString;
            if (base && !/^https?:\/\//i.test(urlString)) {
                var b = typeof base === 'string' ? base : base.href;
                if (urlString.charAt(0) === '/') {
                    var m = b.match(/^(https?:\/\/[^\/]+)/);
                    fullUrl = m ? m[1] + urlString : urlString;
                } else {
                    fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString;
                }
            }
            var parsed = __native_parse_url(fullUrl);
            var data = JSON.parse(parsed);
            this.href = fullUrl;
            this.protocol = data.protocol;
            this.host = data.host;
            this.hostname = data.hostname;
            this.port = data.port;
            this.pathname = data.pathname;
            this.search = data.search;
            this.hash = data.hash;
            this.origin = data.protocol + '//' + data.host;
            this.searchParams = new URLSearchParams(data.search || '');
        };
        URL.prototype.toString = function() { return this.href; };

        var URLSearchParams = function(init) {
            this._params = {};
            var self = this;
            if (init && typeof init === 'object' && !Array.isArray(init)) {
                Object.keys(init).forEach(function(key) { self._params[key] = String(init[key]); });
            } else if (typeof init === 'string') {
                init.replace(/^\?/, '').split('&').forEach(function(pair) {
                    var parts = pair.split('=');
                    if (parts[0]) self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
                });
            }
        };
        URLSearchParams.prototype.toString = function() {
            var self = this;
            return Object.keys(this._params).map(function(key) {
                return encodeURIComponent(key) + '=' + encodeURIComponent(self._params[key]);
            }).join('&');
        };
        URLSearchParams.prototype.get = function(key) { return this._params.hasOwnProperty(key) ? this._params[key] : null; };
        URLSearchParams.prototype.set = function(key, value) { this._params[key] = String(value); };
        URLSearchParams.prototype.append = function(key, value) { this._params[key] = String(value); };
        URLSearchParams.prototype.has = function(key) { return this._params.hasOwnProperty(key); };
        URLSearchParams.prototype.delete = function(key) { delete this._params[key]; };
        URLSearchParams.prototype.keys = function() { return Object.keys(this._params); };
        URLSearchParams.prototype.values = function() {
            var self = this;
            return Object.keys(this._params).map(function(k) { return self._params[k]; });
        };
        URLSearchParams.prototype.entries = function() {
            var self = this;
            return Object.keys(this._params).map(function(k) { return [k, self._params[k]]; });
        };
        URLSearchParams.prototype.forEach = function(callback) {
            var self = this;
            Object.keys(this._params).forEach(function(key) { callback(self._params[key], key, self); });
        };
        URLSearchParams.prototype.getAll = function(key) {
            return this._params.hasOwnProperty(key) ? [this._params[key]] : [];
        };
        URLSearchParams.prototype.sort = function() {
            var sorted = {};
            var self = this;
            Object.keys(this._params).sort().forEach(function(k) { sorted[k] = self._params[k]; });
            this._params = sorted;
        };
    """.trimIndent()

    private fun cryptoPolyfill() = """
        var WordArray = {
            init: function(words, sigBytes) {
                this.words = words || [];
                this.sigBytes = sigBytes != undefined ? sigBytes : this.words.length * 4;
            },
            toString: function(encoder) {
                return (encoder || CryptoJS.enc.Hex).stringify(this);
            },
            concat: function(wordArray) {
                var thisWords = this.words;
                var thatWords = wordArray.words;
                var thisSigBytes = this.sigBytes;
                var thatSigBytes = wordArray.sigBytes;

                this.clamp();

                for (var i = 0; i < thatSigBytes; i++) {
                    var thatByte = (thatWords[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
                    thisWords[(thisSigBytes + i) >>> 2] |= thatByte << (24 - ((thisSigBytes + i) % 4) * 8);
                }
                this.sigBytes += thatSigBytes;
                return this;
            },
            clamp: function() {
                var words = this.words;
                var sigBytes = this.sigBytes;
                if (sigBytes % 4) {
                    words[sigBytes >>> 2] &= 0xffffffff << (32 - (sigBytes % 4) * 8);
                }
                words.length = Math.ceil(sigBytes / 4);
                return this;
            },
            clone: function() {
                return __wordArrayCreate(this.words.slice(0), this.sigBytes);
            }
        };

        function __wordArrayCreate(words, sigBytes) {
            var wa = Object.create(WordArray);
            wa.init(words, sigBytes);
            return wa;
        }

        function __isWordArray(value) {
            return value && typeof value === 'object' && Array.isArray(value.words) && typeof value.sigBytes === 'number';
        }

        function __copyUint8Array(bytes) {
            bytes = __toUint8Array(bytes);
            var copy = new Uint8Array(bytes.length);
            copy.set(bytes);
            return copy;
        }

        function __toUint8Array(data) {
            if (!data) return new Uint8Array(0);
            if (data instanceof Uint8Array) return data;
            if (data instanceof ArrayBuffer) return new Uint8Array(data);
            if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(data)) {
                return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
            }
            if (Array.isArray(data)) return new Uint8Array(data);
            if (typeof data.length === 'number') return new Uint8Array(Array.prototype.slice.call(data));
            return new Uint8Array(0);
        }

        function __bytesToArrayBuffer(bytes) {
            return __copyUint8Array(bytes).buffer;
        }

        function __wordArrayToBytes(wordArray) {
            if (!__isWordArray(wordArray)) return typeof wordArray === 'string' ? new TextEncoder().encode(wordArray) : __toUint8Array(wordArray);
            var bytes = new Uint8Array(wordArray.sigBytes);
            for (var i = 0; i < wordArray.sigBytes; i++) {
                bytes[i] = (wordArray.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
            }
            return bytes;
        }

        function __bytesToWordArray(bytes) {
            bytes = __toUint8Array(bytes);
            var words = [];
            for (var i = 0; i < bytes.length; i++) {
                words[i >>> 2] |= (bytes[i] & 0xff) << (24 - (i % 4) * 8);
            }
            return __wordArrayCreate(words, bytes.length);
        }

        function __normalizeWordArrayInput(value) {
            if (__isWordArray(value)) return __wordArrayToBytes(value);
            if (typeof value === 'string') return new TextEncoder().encode(value);
            return __toUint8Array(value);
        }

        function __bytesToHex(bytes) {
            bytes = __toUint8Array(bytes);
            var out = [];
            for (var i = 0; i < bytes.length; i++) {
                var hex = bytes[i].toString(16);
                out.push(hex.length < 2 ? '0' + hex : hex);
            }
            return out.join('');
        }

        function __hexToBytes(hex) {
            hex = String(hex || '').replace(/[^0-9a-fA-F]/g, '');
            if (hex.length % 2) hex = '0' + hex;
            var bytes = new Uint8Array(hex.length / 2);
            for (var i = 0; i < hex.length; i += 2) {
                bytes[i / 2] = parseInt(hex.substr(i, 2), 16) & 0xff;
            }
            return bytes;
        }

        function __concatBytes() {
            var total = 0;
            var parts = [];
            for (var i = 0; i < arguments.length; i++) {
                var part = __toUint8Array(arguments[i]);
                parts.push(part);
                total += part.length;
            }
            var out = new Uint8Array(total);
            var offset = 0;
            for (var j = 0; j < parts.length; j++) {
                out.set(parts[j], offset);
                offset += parts[j].length;
            }
            return out;
        }

        function __normalizeHashName(hash) {
            var name = hash && hash.name ? hash.name : hash;
            name = String(name || 'SHA-256').toUpperCase().replace(/[^A-Z0-9]/g, '');
            if (name === 'SHA1' || name === 'SHA256' || name === 'SHA384' || name === 'SHA512' || name === 'MD5') return name;
            throw new Error('Unsupported hash algorithm: ' + name);
        }

        function __normalizeAlgorithmName(algo) {
            var name = algo && algo.name ? algo.name : algo;
            name = String(name || '').toUpperCase();
            if (name.indexOf('AES-GCM') >= 0) return 'AES-GCM';
            if (name.indexOf('AES-CBC') >= 0) return 'AES-CBC';
            if (name.indexOf('AES-ECB') >= 0 || name === 'ECB') return 'AES-ECB';
            if (name.indexOf('PBKDF2') >= 0) return 'PBKDF2';
            if (name.indexOf('HMAC') >= 0) return 'HMAC';
            if (name.indexOf('RSASSA-PKCS1') >= 0) return 'RSASSA-PKCS1-V1_5';
            if (name.indexOf('ECDSA') >= 0) return 'ECDSA';
            return name;
        }

        function __aesModeName(mode, padding) {
            var normalized = __normalizeAlgorithmName(mode || 'AES-CBC');
            if (padding === CryptoJS.pad.NoPadding || padding === 'NoPadding') normalized += '-NoPadding';
            return normalized;
        }

        function __nativeDigestBytes(hash, dataBytes) {
            if (typeof __crypto_digest_hex_raw === 'undefined') throw new Error('Native digest bridge is unavailable');
            return __hexToBytes(__crypto_digest_hex_raw(__normalizeHashName(hash), __bytesToHex(dataBytes)));
        }

        function __nativeHmacBytes(hash, keyBytes, dataBytes) {
            if (typeof __crypto_hmac_hex_raw === 'undefined') throw new Error('Native HMAC bridge is unavailable');
            return __hexToBytes(__crypto_hmac_hex_raw(__normalizeHashName(hash), __bytesToHex(keyBytes), __bytesToHex(dataBytes)));
        }

        function __nativePbkdf2Bytes(passwordBytes, saltBytes, iterations, keySizeBits, hash) {
            if (typeof __crypto_pbkdf2_hex === 'undefined') throw new Error('Native PBKDF2 bridge is unavailable');
            return __hexToBytes(__crypto_pbkdf2_hex(__bytesToHex(passwordBytes), __bytesToHex(saltBytes), iterations, keySizeBits, __normalizeHashName(hash)));
        }

        function __nativeAesBytes(encrypt, mode, keyBytes, ivBytes, dataBytes) {
            var fn = encrypt ? __crypto_aes_encrypt_hex : __crypto_aes_decrypt_hex;
            if (typeof fn === 'undefined') throw new Error('Native AES bridge is unavailable');
            return __hexToBytes(fn(mode, __bytesToHex(keyBytes), __bytesToHex(ivBytes), __bytesToHex(dataBytes)));
        }

        function __evpKdf(passwordBytes, saltBytes, keySizeBytes, ivSizeBytes) {
            var targetSize = keySizeBytes + ivSizeBytes;
            var derived = new Uint8Array(targetSize);
            var block = new Uint8Array(0);
            var offset = 0;
            while (offset < targetSize) {
                block = __nativeDigestBytes('MD5', __concatBytes(block, passwordBytes, saltBytes || new Uint8Array(0)));
                var take = Math.min(block.length, targetSize - offset);
                derived.set(block.subarray(0, take), offset);
                offset += take;
            }
            return {
                key: derived.subarray(0, keySizeBytes),
                iv: derived.subarray(keySizeBytes, keySizeBytes + ivSizeBytes)
            };
        }

        function __opensslSaltHeader() {
            return new Uint8Array([83, 97, 108, 116, 101, 100, 95, 95]);
        }

        function __hasOpenSslSaltHeader(bytes) {
            var header = __opensslSaltHeader();
            if (!bytes || bytes.length < 16) return false;
            for (var i = 0; i < header.length; i++) {
                if (bytes[i] !== header[i]) return false;
            }
            return true;
        }

        function __makeCipherParams(ciphertext, key, iv, salt, mode) {
            return {
                ciphertext: __bytesToWordArray(ciphertext),
                key: key ? __bytesToWordArray(key) : undefined,
                iv: iv ? __bytesToWordArray(iv) : undefined,
                salt: salt ? __bytesToWordArray(salt) : undefined,
                mode: mode,
                toString: function(formatter) {
                    return (formatter || CryptoJS.format.OpenSSL).stringify(this);
                }
            };
        }

        var CryptoJS = {
            enc: {
                Hex: {
                    stringify: function(wordArray) {
                        return __bytesToHex(__wordArrayToBytes(wordArray));
                    },
                    parse: function(hexStr) {
                        return __bytesToWordArray(__hexToBytes(hexStr));
                    }
                },
                Utf8: {
                    stringify: function(wordArray) {
                        return new TextDecoder('utf-8').decode(__wordArrayToBytes(wordArray));
                    },
                    parse: function(utf8Str) {
                        return __bytesToWordArray(new TextEncoder().encode(String(utf8Str)));
                    }
                },
                Latin1: {
                    stringify: function(wordArray) {
                        var bytes = __wordArrayToBytes(wordArray);
                        var out = '';
                        for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
                        return out;
                    },
                    parse: function(str) {
                        str = String(str || '');
                        var bytes = new Uint8Array(str.length);
                        for (var i = 0; i < str.length; i++) bytes[i] = str.charCodeAt(i) & 0xff;
                        return __bytesToWordArray(bytes);
                    }
                },
                Base64: {
                    stringify: function(wordArray) {
                        var bytes = __wordArrayToBytes(wordArray);
                        var binaryStr = '';
                        for (var j = 0; j < bytes.length; j++) binaryStr += String.fromCharCode(bytes[j]);
                        return btoa(binaryStr);
                    },
                    parse: function(base64Str) {
                        var binaryStr = atob(String(base64Str || ''));
                        var bytes = new Uint8Array(binaryStr.length);
                        for (var i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i) & 0xff;
                        return __bytesToWordArray(bytes);
                    }
                },
                Base64url: {
                    stringify: function(wordArray) {
                        return CryptoJS.enc.Base64.stringify(wordArray).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
                    },
                    parse: function(str) {
                        str = String(str || '').replace(/-/g, '+').replace(/_/g, '/');
                        while (str.length % 4) str += '=';
                        return CryptoJS.enc.Base64.parse(str);
                    }
                }
            },
            lib: {
                WordArray: {
                    create: function(words, sigBytes) {
                        if (words == null) return __wordArrayCreate([], sigBytes || 0);
                        if (__isWordArray(words)) return words.clone();
                        if (typeof words === 'string') return CryptoJS.enc.Utf8.parse(words);
                        if (words instanceof ArrayBuffer || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(words))) {
                            var bytes = __toUint8Array(words);
                            return __bytesToWordArray(sigBytes != undefined ? bytes.subarray(0, sigBytes) : bytes);
                        }
                        return __wordArrayCreate(words, sigBytes);
                    },
                    random: function(nBytes) {
                        var bytes = new Uint8Array(nBytes || 0);
                        globalThis.crypto.getRandomValues(bytes);
                        return __bytesToWordArray(bytes);
                    }
                },
                CipherParams: {
                    create: function(params) {
                        params = params || {};
                        params.toString = params.toString || function(formatter) {
                            return (formatter || CryptoJS.format.OpenSSL).stringify(this);
                        };
                        return params;
                    }
                }
            },
            format: {
                OpenSSL: {
                    stringify: function(cipherParams) {
                        var cipherBytes = __wordArrayToBytes(cipherParams.ciphertext);
                        var out = cipherParams.salt
                            ? __concatBytes(__opensslSaltHeader(), __wordArrayToBytes(cipherParams.salt), cipherBytes)
                            : cipherBytes;
                        return CryptoJS.enc.Base64.stringify(__bytesToWordArray(out));
                    },
                    parse: function(str) {
                        var bytes = __wordArrayToBytes(CryptoJS.enc.Base64.parse(str));
                        if (__hasOpenSslSaltHeader(bytes)) {
                            return CryptoJS.lib.CipherParams.create({
                                salt: __bytesToWordArray(bytes.subarray(8, 16)),
                                ciphertext: __bytesToWordArray(bytes.subarray(16))
                            });
                        }
                        return CryptoJS.lib.CipherParams.create({ ciphertext: __bytesToWordArray(bytes) });
                    }
                }
            },
            mode: { CBC: 'AES-CBC', GCM: 'AES-GCM', ECB: 'AES-ECB' },
            pad: { Pkcs7: 'Pkcs7', NoPadding: 'NoPadding' },
            algo: { MD5: 'MD5', SHA1: 'SHA1', SHA256: 'SHA256', SHA384: 'SHA384', SHA512: 'SHA512', AES: 'AES' },
            MD5: function(m) { return __bytesToWordArray(__nativeDigestBytes('MD5', __normalizeWordArrayInput(m))); },
            SHA1: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA1', __normalizeWordArrayInput(m))); },
            SHA256: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA256', __normalizeWordArrayInput(m))); },
            SHA384: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA384', __normalizeWordArrayInput(m))); },
            SHA512: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA512', __normalizeWordArrayInput(m))); },
            HmacMD5: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('MD5', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
            HmacSHA1: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA1', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
            HmacSHA256: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA256', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
            HmacSHA384: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA384', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
            HmacSHA512: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA512', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
            PBKDF2: function(pass, salt, options) {
                options = options || {};
                var pBytes = __normalizeWordArrayInput(pass);
                var sBytes = __normalizeWordArrayInput(salt);
                var iter = options.iterations || 1000;
                var kSize = options.keySize || 8;
                var algo = options.hasher || 'SHA1';
                return __bytesToWordArray(__nativePbkdf2Bytes(pBytes, sBytes, iter, kSize * 32, algo));
            },
            AES: {
                encrypt: function(message, key, options) {
                    options = options || {};
                    var data = __normalizeWordArrayInput(message);
                    var kBytes;
                    var ivBytes;
                    var saltBytes;
                    var isPassphrase = typeof key === 'string';
                    if (isPassphrase) {
                        saltBytes = options.salt ? __wordArrayToBytes(options.salt) : __wordArrayToBytes(CryptoJS.lib.WordArray.random(8));
                        var derived = __evpKdf(new TextEncoder().encode(key), saltBytes, 32, 16);
                        kBytes = derived.key;
                        ivBytes = options.iv ? __wordArrayToBytes(options.iv) : derived.iv;
                    } else {
                        kBytes = __wordArrayToBytes(key);
                        ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
                    }
                    var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
                    var resBytes = __nativeAesBytes(true, mode, kBytes, ivBytes, data);
                    return __makeCipherParams(resBytes, kBytes, ivBytes, saltBytes, mode);
                },
                decrypt: function(cipher, key, options) {
                    options = options || {};
                    var cipherParams = typeof cipher === 'string' ? CryptoJS.format.OpenSSL.parse(cipher) : cipher;
                    var data = cipherParams.ciphertext ? __wordArrayToBytes(cipherParams.ciphertext) : __toUint8Array(cipherParams);
                    var kBytes;
                    var ivBytes;
                    var isPassphrase = typeof key === 'string';
                    if (isPassphrase) {
                        var saltBytes = options.salt ? __wordArrayToBytes(options.salt) : (cipherParams.salt ? __wordArrayToBytes(cipherParams.salt) : new Uint8Array(0));
                        var derived = __evpKdf(new TextEncoder().encode(key), saltBytes, 32, 16);
                        kBytes = derived.key;
                        ivBytes = options.iv ? __wordArrayToBytes(options.iv) : derived.iv;
                    } else {
                        kBytes = __wordArrayToBytes(key);
                        ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
                    }
                    var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
                    return __bytesToWordArray(__nativeAesBytes(false, mode, kBytes, ivBytes, data));
                }
            }
        };
        globalThis.CryptoJS = CryptoJS;

        function __makeCryptoKey(type, algorithm, extractable, usages, rawBytes) {
            return {
                type: type,
                extractable: !!extractable,
                algorithm: algorithm,
                usages: usages || [],
                _raw: __copyUint8Array(rawBytes)
            };
        }

        function __webCryptoAlgorithm(algo) {
            var name = __normalizeAlgorithmName(algo);
            var out = { name: name };
            if (algo && typeof algo === 'object' && algo.length) out.length = algo.length;
            if (algo && typeof algo === 'object' && algo.hash) out.hash = { name: __normalizeHashName(algo.hash) };
            return out;
        }

        function __signatureAlgorithmName(algo, key) {
            var name = __normalizeAlgorithmName(algo || (key && key.algorithm));
            var hash = algo && algo.hash ? __normalizeHashName(algo.hash) : (key && key.algorithm && key.algorithm.hash ? key.algorithm.hash.name : 'SHA256');
            if (name === 'RSASSA-PKCS1-V1_5') return 'RSASSA-PKCS1-V1_5-' + hash;
            if (name === 'ECDSA') return 'ECDSA-' + hash;
            return name;
        }

        globalThis.crypto = {
            subtle: {
                digest: async function(algo, data) {
                    return __bytesToArrayBuffer(__nativeDigestBytes(algo, __toUint8Array(data)));
                },
                importKey: async function(fmt, data, algo, extractable, usages) {
                    fmt = String(fmt || 'raw').toLowerCase();
                    if (fmt !== 'raw' && fmt !== 'pkcs8' && fmt !== 'spki') throw new Error('Unsupported key format: ' + fmt);
                    var algorithm = __webCryptoAlgorithm(algo || {});
                    var type = fmt === 'spki' ? 'public' : (fmt === 'pkcs8' ? 'private' : 'secret');
                    return __makeCryptoKey(type, algorithm, extractable, usages || [], __toUint8Array(data));
                },
                exportKey: async function(fmt, key) {
                    fmt = String(fmt || 'raw').toLowerCase();
                    if (fmt !== 'raw' && fmt !== 'pkcs8' && fmt !== 'spki') throw new Error('Unsupported key format: ' + fmt);
                    return __bytesToArrayBuffer(key._raw);
                },
                generateKey: async function(algo, extractable, usages) {
                    var algorithm = __webCryptoAlgorithm(algo || {});
                    if (algorithm.name !== 'AES-CBC' && algorithm.name !== 'AES-GCM' && algorithm.name !== 'HMAC') {
                        throw new Error('Unsupported generateKey algorithm: ' + algorithm.name);
                    }
                    var length = algorithm.length || 256;
                    var bytes = new Uint8Array(length / 8);
                    globalThis.crypto.getRandomValues(bytes);
                    return __makeCryptoKey('secret', algorithm, extractable, usages || [], bytes);
                },
                deriveBits: async function(params, key, len) {
                    if (__normalizeAlgorithmName(params) !== 'PBKDF2') throw new Error('Only PBKDF2 deriveBits is supported');
                    var pBytes = __toUint8Array(key._raw);
                    var sBytes = __toUint8Array(params.salt);
                    var hash = params.hash || 'SHA-256';
                    return __bytesToArrayBuffer(__nativePbkdf2Bytes(pBytes, sBytes, params.iterations || 1000, len, hash));
                },
                deriveKey: async function(params, key, derivedKeyAlgo, extractable, usages) {
                    var algorithm = __webCryptoAlgorithm(derivedKeyAlgo || {});
                    var length = algorithm.length || 256;
                    var raw = await globalThis.crypto.subtle.deriveBits(params, key, length);
                    return __makeCryptoKey('secret', algorithm, extractable, usages || [], new Uint8Array(raw));
                },
                encrypt: async function(params, key, data) {
                    var mode = __normalizeAlgorithmName(params);
                    if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported encrypt algorithm: ' + mode);
                    if (mode === 'AES-GCM' && params.tagLength && params.tagLength !== 128) throw new Error('Only 128-bit AES-GCM tags are supported');
                    if (mode === 'AES-GCM' && params.additionalData) throw new Error('AES-GCM additionalData is not supported');
                    var ivBytes = __toUint8Array(params.iv || new Uint8Array(0));
                    return __bytesToArrayBuffer(__nativeAesBytes(true, mode, __toUint8Array(key._raw), ivBytes, __toUint8Array(data)));
                },
                decrypt: async function(params, key, data) {
                    var mode = __normalizeAlgorithmName(params);
                    if (mode !== 'AES-CBC' && mode !== 'AES-GCM') throw new Error('Unsupported decrypt algorithm: ' + mode);
                    if (mode === 'AES-GCM' && params.tagLength && params.tagLength !== 128) throw new Error('Only 128-bit AES-GCM tags are supported');
                    if (mode === 'AES-GCM' && params.additionalData) throw new Error('AES-GCM additionalData is not supported');
                    var ivBytes = __toUint8Array(params.iv || new Uint8Array(0));
                    return __bytesToArrayBuffer(__nativeAesBytes(false, mode, __toUint8Array(key._raw), ivBytes, __toUint8Array(data)));
                },
                sign: async function(algo, key, data) {
                    if (__normalizeAlgorithmName(algo || key.algorithm) === 'HMAC' || key.algorithm.name === 'HMAC') {
                        var hash = (algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256';
                        return __bytesToArrayBuffer(__nativeHmacBytes(hash, __toUint8Array(key._raw), __toUint8Array(data)));
                    }
                    if (typeof __crypto_sign_hex === 'undefined') throw new Error('Native signature bridge is unavailable');
                    var sigHex = __crypto_sign_hex(__signatureAlgorithmName(algo, key), __bytesToHex(key._raw), __bytesToHex(__toUint8Array(data)));
                    return __bytesToArrayBuffer(__hexToBytes(sigHex));
                },
                verify: async function(algo, key, sig, data) {
                    if (__normalizeAlgorithmName(algo || key.algorithm) === 'HMAC' || key.algorithm.name === 'HMAC') {
                        var expected = __nativeHmacBytes((algo && algo.hash) || (key.algorithm && key.algorithm.hash) || 'SHA-256', __toUint8Array(key._raw), __toUint8Array(data));
                        var actual = __toUint8Array(sig);
                        if (expected.length !== actual.length) return false;
                        var diff = 0;
                        for (var i = 0; i < expected.length; i++) diff |= expected[i] ^ actual[i];
                        return diff === 0;
                    }
                    if (typeof __crypto_verify_hex === 'undefined') throw new Error('Native signature bridge is unavailable');
                    return __crypto_verify_hex(__signatureAlgorithmName(algo, key), __bytesToHex(key._raw), __bytesToHex(__toUint8Array(sig)), __bytesToHex(__toUint8Array(data)));
                }
            },
            getRandomValues: function(arr) {
                if (!arr) return arr;
                var byteLength = arr.byteLength != undefined ? arr.byteLength : arr.length;
                if (!byteLength) return arr;
                if (typeof __crypto_get_random_values_hex === 'undefined') throw new Error('Native random bridge is unavailable');
                var random = __hexToBytes(__crypto_get_random_values_hex(byteLength));
                if (arr.buffer && arr.byteLength != undefined) {
                    new Uint8Array(arr.buffer, arr.byteOffset || 0, arr.byteLength).set(random);
                } else {
                    for (var i = 0; i < arr.length; i++) arr[i] = random[i] || 0;
                }
                return arr;
            },
            randomUUID: function() {
                var b = new Uint8Array(16);
                globalThis.crypto.getRandomValues(b);
                b[6] = (b[6] & 0x0f) | 0x40;
                b[8] = (b[8] & 0x3f) | 0x80;
                var h = __bytesToHex(b);
                return h.substr(0, 8) + '-' + h.substr(8, 4) + '-' + h.substr(12, 4) + '-' + h.substr(16, 4) + '-' + h.substr(20);
            }
        };

        // WebAssembly placeholder
        globalThis.WebAssembly = {
            instantiate: async function(bufferSource, importObject) {
                console.warn("WebAssembly.instantiate called (placeholder)");
                return { instance: { exports: {} }, module: {} };
            }
        };
    """.trimIndent()

    private fun textEncoderPolyfill() = """
        if (typeof TextEncoder === 'undefined') {
            globalThis.TextEncoder = function() {};
            TextEncoder.prototype.encode = function(str) {
                var hex = __crypto_utf8_to_hex(str);
                var bytes = new Uint8Array(hex.length / 2);
                for (var i = 0; i < hex.length; i += 2) {
                    bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
                }
                return bytes;
            };
        }
        if (typeof TextDecoder === 'undefined') {
            globalThis.TextDecoder = function() {};
            TextDecoder.prototype.decode = function(data) {
                var bytes = data;
                if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
                var hex = '';
                for (var i = 0; i < bytes.length; i++) {
                    hex += bytes[i].toString(16).padStart(2, '0');
                }
                return __crypto_hex_to_utf8(hex);
            };
        }
    """.trimIndent()

    private fun cheerioPolyfill() = """
        var cheerio = {
            load: function(html) {
                var docId = __cheerio_load(html);
                var $ = function(selector, context) {
                    if (selector && selector._elementIds) return selector;
                    if (context && context._elementIds && context._elementIds.length > 0) {
                        var allIds = [];
                        for (var i = 0; i < context._elementIds.length; i++) {
                            var childIdsJson = __cheerio_find(docId, context._elementIds[i], selector);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    }
                    return createCheerioWrapper(docId, selector);
                };
                $.html = function(el) {
                    if (el && el._elementIds && el._elementIds.length > 0) {
                        return __cheerio_html(docId, el._elementIds[0]);
                    }
                    return __cheerio_html(docId, '');
                };
                return $;
            }
        };

        function createCheerioWrapper(docId, selector) {
            var elementIds;
            if (typeof selector === 'string') {
                var idsJson = __cheerio_select(docId, selector);
                elementIds = JSON.parse(idsJson);
            } else {
                elementIds = [];
            }
            return createCheerioWrapperFromIds(docId, elementIds);
        }

        function createCheerioWrapperFromIds(docId, ids) {
            var wrapper = {
                _docId: docId,
                _elementIds: ids,
                length: ids.length,
                each: function(callback) {
                    for (var i = 0; i < ids.length; i++) {
                        var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                        callback.call(elWrapper, i, elWrapper);
                    }
                    return wrapper;
                },
                find: function(sel) {
                    var allIds = [];
                    for (var i = 0; i < ids.length; i++) {
                        var childIdsJson = __cheerio_find(docId, ids[i], sel);
                        var childIds = JSON.parse(childIdsJson);
                        allIds = allIds.concat(childIds);
                    }
                    return createCheerioWrapperFromIds(docId, allIds);
                },
                text: function() {
                    if (ids.length === 0) return '';
                    return __cheerio_text(docId, ids.join(','));
                },
                html: function() {
                    if (ids.length === 0) return '';
                    return __cheerio_inner_html(docId, ids[0]);
                },
                attr: function(name) {
                    if (ids.length === 0) return undefined;
                    var val = __cheerio_attr(docId, ids[0], name);
                    return val === '__UNDEFINED__' ? undefined : val;
                },
                first: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[0]] : []); },
                last: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[ids.length - 1]] : []); },
                next: function() {
                    var nextIds = [];
                    for (var i = 0; i < ids.length; i++) {
                        var nextId = __cheerio_next(docId, ids[i]);
                        if (nextId && nextId !== '__NONE__') nextIds.push(nextId);
                    }
                    return createCheerioWrapperFromIds(docId, nextIds);
                },
                prev: function() {
                    var prevIds = [];
                    for (var i = 0; i < ids.length; i++) {
                        var prevId = __cheerio_prev(docId, ids[i]);
                        if (prevId && prevId !== '__NONE__') prevIds.push(prevId);
                    }
                    return createCheerioWrapperFromIds(docId, prevIds);
                },
                eq: function(index) {
                    if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                    return createCheerioWrapperFromIds(docId, []);
                },
                get: function(index) {
                    if (typeof index === 'number') {
                        if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                        return undefined;
                    }
                    return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); });
                },
                map: function(callback) {
                    var results = [];
                    for (var i = 0; i < ids.length; i++) {
                        var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                        var result = callback.call(elWrapper, i, elWrapper);
                        if (result !== undefined && result !== null) results.push(result);
                    }
                    return {
                        length: results.length,
                        get: function(index) { return typeof index === 'number' ? results[index] : results; },
                        toArray: function() { return results; }
                    };
                },
                filter: function(selectorOrCallback) {
                    if (typeof selectorOrCallback === 'function') {
                        var filteredIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                            if (result) filteredIds.push(ids[i]);
                        }
                        return createCheerioWrapperFromIds(docId, filteredIds);
                    }
                    return wrapper;
                },
                children: function(sel) { return this.find(sel || '*'); },
                parent: function() { return createCheerioWrapperFromIds(docId, []); },
                toArray: function() { return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); }); }
            };
            return wrapper;
        }
    """.trimIndent()

    private fun requirePolyfill() = """
        var require = function(moduleName) {
            if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') {
                return cheerio;
            }
            if (moduleName === 'crypto-js') {
                return CryptoJS;
            }
            throw new Error("Module '" + moduleName + "' is not available");
        };
    """.trimIndent()

    private fun arrayPolyfill() = """
        if (!Array.prototype.flat) {
            Array.prototype.flat = function(depth) {
                depth = depth === undefined ? 1 : Math.floor(depth);
                if (depth < 1) return Array.prototype.slice.call(this);
                return (function flatten(arr, d) {
                    return d > 0
                        ? arr.reduce(function(acc, val) { return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val); }, [])
                        : arr.slice();
                })(this, depth);
            };
        }

        if (!Array.prototype.flatMap) {
            Array.prototype.flatMap = function(callback, thisArg) { return this.map(callback, thisArg).flat(); };
        }
    """.trimIndent()

    private fun objectPolyfill() = """
        if (!Object.entries) {
            Object.entries = function(obj) {
                var result = [];
                for (var key in obj) {
                    if (obj.hasOwnProperty(key)) result.push([key, obj[key]]);
                }
                return result;
            };
        }

        if (!Object.fromEntries) {
            Object.fromEntries = function(entries) {
                var result = {};
                for (var i = 0; i < entries.length; i++) {
                    result[entries[i][0]] = entries[i][1];
                }
                return result;
            };
        }
    """.trimIndent()

    private fun stringPolyfill() = """
        if (!String.prototype.replaceAll) {
            String.prototype.replaceAll = function(search, replace) {
                if (search instanceof RegExp) {
                    if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
                    return this.replace(search, replace);
                }
                return this.split(search).join(replace);
            };
        }
    """.trimIndent()
}

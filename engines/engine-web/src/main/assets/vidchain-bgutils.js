/* vidchain-bgutils.js - built by tools/build_bgutils.py from bgutils-js 4.0.3 (https://registry.npmjs.org/bgutils-js/-/bgutils-js-4.0.3.tgz, sha512 nvhrzqRYqFcC3AJbf1fENc/jzyMk95wH9UC9ruQzTZCq+itYWuHi2a9leK/bC1lLfi+R4a28wJZxpD5KiTFf+w==).
 * BgUtils by LuanRT, MIT licence:
 * MIT License
 * 
 * Copyright (c) 2024 LuanRT
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
(function(){
'use strict';
// ---- dist/utils/constants.js ----
const GOOG_BASE_URL = 'https://jnn-pa.googleapis.com';
const YT_BASE_URL = 'https://www.youtube.com';
const GOOG_API_KEY = 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw';
const USER_AGENT = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)';

// ---- dist/utils/helpers.js ----
const base64urlCharRegex = /[-_.]/g;
const base64urlToBase64Map = {
    '-': '+',
    _: '/',
    '.': '='
};
class DeferredPromise {
    promise;
    resolve;
    reject;
    constructor() {
        this.promise = new Promise((resolve, reject) => {
            this.resolve = resolve;
            this.reject = reject;
        });
    }
}
class BgError extends TypeError {
    info;
    constructor(message, info) {
        super(message);
        this.name = 'BgError';
        if (info)
            this.info = info;
    }
}
function base64ToU8(base64) {
    let base64Mod;
    if (base64urlCharRegex.test(base64)) {
        base64Mod = base64.replace(base64urlCharRegex, function (match) {
            return base64urlToBase64Map[match];
        });
    }
    else {
        base64Mod = base64;
    }
    base64Mod = atob(base64Mod);
    return new Uint8Array([...base64Mod].map((char) => char.charCodeAt(0)));
}
function u8ToBase64(u8, base64url = false) {
    const result = btoa(String.fromCharCode(...u8));
    if (base64url) {
        return result
            .replace(/\+/g, '-')
            .replace(/\//g, '_');
    }
    return result;
}
function parseLooseJSON(looseJson) {
    const sanitizedString = looseJson.replace(/\\x([0-9A-Fa-f]{2})/g, (_match, hex) => {
        return String.fromCharCode(parseInt(hex, 16));
    });
    let jsonStr = sanitizedString.replace(/,\s*([\]}])/g, '$1');
    jsonStr = jsonStr.replace(/'((?:[^'\\]|\\[\s\S])*)'/g, (_match, innerStr) => {
        const unescaped = innerStr.replace(/\\'/g, '\'');
        return JSON.stringify(unescaped);
    });
    // just in case
    jsonStr = jsonStr.replace(/([{,]\s*)([a-zA-Z0-9_$]+)\s*:/g, '$1"$2":');
    const parsedData = JSON.parse(jsonStr);
    for (const key in parsedData) {
        const val = parsedData[key];
        if (typeof val === 'string' && (val.trim().startsWith('{') || val.trim().startsWith('['))) {
            try {
                parsedData[key] = JSON.parse(val);
            }
            catch { /** no-op */ }
        }
    }
    return parsedData;
}
function isBrowser() {
    const isBrowser = typeof window !== 'undefined'
        && typeof window.document !== 'undefined'
        && typeof window.document.createElement !== 'undefined'
        && typeof window.HTMLElement !== 'undefined'
        && typeof window.navigator !== 'undefined'
        && typeof window.getComputedStyle === 'function'
        && typeof window.requestAnimationFrame === 'function'
        && typeof window.matchMedia === 'function';
    const hasValidWindow = Object.getOwnPropertyDescriptor(globalThis, 'window')?.get?.toString().includes('[native code]') ?? false;
    return isBrowser && hasValidWindow;
}
function getHeaders() {
    const headers = {
        'content-type': 'application/json+protobuf',
        'x-goog-api-key': GOOG_API_KEY,
        'x-user-agent': 'grpc-web-javascript/0.1'
    };
    if (!isBrowser()) {
        headers['user-agent'] = USER_AGENT;
    }
    return headers;
}
function buildURL(endpointName, useYouTubeAPI) {
    return `${useYouTubeAPI ? YT_BASE_URL : GOOG_BASE_URL}/${useYouTubeAPI ? 'api/jnn/v1' : '$rpc/google.internal.waa.v1.Waa'}/${endpointName}`;
}

// ---- dist/utils/EventEmitterLike.js ----
class EventEmitterLike {
    #listeners = new Map();
    #onceWrappers = new Map();
    emit(type, ...args) {
        const listeners = this.#listeners.get(type);
        if (!listeners || listeners.size === 0)
            return;
        // Snaapshot listeners so removals during emit do not affect current iteration.
        for (const listener of [...listeners]) {
            listener(...args);
        }
    }
    on(type, listener) {
        let listeners = this.#listeners.get(type);
        if (!listeners) {
            listeners = new Set();
            this.#listeners.set(type, listeners);
        }
        listeners.add(listener);
    }
    once(type, listener) {
        const wrapper = (...args) => {
            this.off(type, listener);
            listener(...args);
        };
        let wrappersByType = this.#onceWrappers.get(listener);
        if (!wrappersByType) {
            wrappersByType = new Map();
            this.#onceWrappers.set(listener, wrappersByType);
        }
        wrappersByType.set(type, wrapper);
        this.on(type, wrapper);
    }
    off(type, listener) {
        const listeners = this.#listeners.get(type);
        if (!listeners)
            return;
        let target = listener;
        const wrappersByType = this.#onceWrappers.get(listener);
        if (wrappersByType) {
            const onceWrapper = wrappersByType.get(type);
            if (onceWrapper) {
                target = onceWrapper;
                wrappersByType.delete(type);
                if (wrappersByType.size === 0)
                    this.#onceWrappers.delete(listener);
            }
        }
        listeners.delete(target);
        if (listeners.size === 0)
            this.#listeners.delete(type);
    }
    removeAllListeners(type) {
        if (!type) {
            this.#listeners.clear();
            this.#onceWrappers.clear();
            return;
        }
        this.#listeners.delete(type);
        for (const [listener, wrappersByType] of this.#onceWrappers.entries()) {
            wrappersByType.delete(type);
            if (wrappersByType.size === 0)
                this.#onceWrappers.delete(listener);
        }
    }
}

// ---- dist/core/BotGuardClient.js ----
class BotGuardClient extends EventEmitterLike {
    vm;
    program;
    userInteractionElement;
    syncSnapshotFunction;
    deferredVmFunctions = new DeferredPromise();
    defaultTimeout = 3000;
    on(type, listener) {
        super.on(type, listener);
    }
    off(type, listener) {
        super.off(type, listener);
    }
    constructor(options) {
        super();
        if (!options.globalObject || !options.globalName || !options.program) {
            throw new BgError('Invalid options', { options });
        }
        this.userInteractionElement = options.userInteractionElement;
        this.vm = options.globalObject[options.globalName];
        this.program = options.program;
    }
    /**
     * Factory method to create and load a BotGuardClient instance.
     * @param options - Configuration options for the BotGuardClient.
     * @returns A loaded BotGuardClient instance.
     */
    static async create(options) {
        return await new BotGuardClient(options).load();
    }
    async load() {
        if (!this.vm)
            throw new BgError('EGOU: BotGuard unavailable');
        if (!this.vm.a)
            throw new BgError('ELIU: BotGuard initialization function unavailable');
        const vmSetupCallback = (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) => {
            this.deferredVmFunctions.resolve({
                asyncSnapshotFunction,
                shutdownFunction,
                passEventFunction,
                checkCameraFunction
            });
        };
        /**
         * NOTE:
         * The descriptions in the following functions are referring to the respective
         * Google Clearcut (https://www.google.com/log?format=json&hasfast=true)
         * label used by each function in the original code.
         */
        /**
         * "/client_streamz/bg/el" (botguard/event_log)
         */
        const logEvent = (event, elapsedTime) => {
            this.emit('record-bg-event', { event, elapsedTime });
        };
        /**
         * "/client_streamz/bg/cec" (botguard/client_error_count)
         */
        const incrementClientErrorCount = (errorCode) => {
            this.emit('increment-client-error-count', { errorCode });
        };
        /**
         * "/client_streamz/bg/od/p" (botguard/output_data/payload_size maybe?)
         */
        const recordPayloadSize = (payloadSize) => {
            this.emit('record-payload-size', { payloadSize });
        };
        /**
         * "/client_streamz/bg/od/n"
         */
        const recordLatency = (latency, et) => {
            this.emit('record-latency', { latency, et });
        };
        /**
         * "/client_streamz/bg/ec" (botguard/event_count)
         */
        const incrementEventCount = (event) => {
            this.emit('increment-bg-event-count', { event });
        };
        const loggerFunctions = [
            logEvent,
            incrementClientErrorCount,
            recordPayloadSize,
            recordLatency,
            incrementEventCount
        ];
        /**
         * Telemetry logging callback passed to the VM.
         * @NOTE
         * This is a direct port of the minified code, minus the telemetry throttling logic.
         * I don't know what the event flags mean, but I noticed that 'k' is spammed every time
         * the mouse or keyboard is used on the YouTube page.
         * Maybe 'k' is for keyboard and 'h' is hardware?
         */
        const vmTelemetryCallback = (latency, eventFlag1, eventFlag2) => {
            let event = 'k';
            if (eventFlag1) {
                event = 'h';
            }
            else if (eventFlag2) {
                event = 'u';
            }
            incrementEventCount(event);
            logEvent(event, latency);
        };
        try {
            this.syncSnapshotFunction = await this.vm.a(this.program, vmSetupCallback, true, this.userInteractionElement, vmTelemetryCallback, [[], []], undefined, false, loggerFunctions)?.[0];
        }
        catch (error) {
            throw new BgError('Could not load program', { error });
        }
        return this;
    }
    /**
     * Calls a VM function with a timeout.
     * @param vmFunctionName - The name of the VM function to execute.
     * @param timeout - The timeout in milliseconds.
     * @param args - The arguments to pass to the VM function.
     */
    async execute(vmFunctionName, timeout, ...args) {
        return await Promise.race([
            (async () => {
                const vmFunctions = await this.deferredVmFunctions.promise;
                const vmFunction = vmFunctions[vmFunctionName];
                if (!vmFunction)
                    throw new BgError(`${vmFunctionName} function not found`);
                return vmFunction(...args);
            })(),
            new Promise((_, reject) => setTimeout(() => reject(new BgError('VM operation timed out')), timeout))
        ]);
    }
    /**
     * Takes a snapshot asynchronously.
     * @returns The snapshot result.
     * @example
     * ```ts
     * const result = await botguard.snapshot({
     *   contentBinding: {
     *     c: "a=6&a2=10&b=SZWDwKVIuixOp7Y4euGTgwckbJA&c=1729143849&d=1&t=7200&c1a=1&c6a=1&c6b=1&hh=HrMb5mRWTyxGJphDr0nW2Oxonh0_wl2BDqWuLHyeKLo",
     *     e: "ENGAGEMENT_TYPE_VIDEO_LIKE",
     *     encryptedVideoId: "P-vC09ZJcnM"
     *    }
     * });
     *
     * console.log(result);
     * ```
     */
    async snapshot(args, timeout = this.defaultTimeout) {
        return await new Promise(async (resolve, reject) => {
            await this.execute('asyncSnapshotFunction', timeout, (response) => resolve(response), [
                args.contentBinding,
                args.signedTimestamp,
                args.webPoSignalOutput,
                args.skipPrivacyBuffer
            ]).catch(reject);
        });
    }
    /**
     * Passes an event to the VM.
     */
    async passEvent(args, timeout = this.defaultTimeout) {
        return this.execute('passEventFunction', timeout, args);
    }
    /**
     * Checks the "camera".
     */
    async checkCamera(args, timeout = this.defaultTimeout) {
        return this.execute('checkCameraFunction', timeout, args);
    }
    /**
     * Shuts down the VM. Once called, the VM is no longer usable.
     */
    async shutdown(timeout = this.defaultTimeout) {
        return this.execute('shutdownFunction', timeout);
    }
    /**
     * Takes a snapshot synchronously.
     * @returns The snapshot result.
     */
    async snapshotSynchronous(args) {
        if (!this.syncSnapshotFunction)
            throw new BgError('Synchronous snapshot function not found');
        return this.syncSnapshotFunction([
            args.contentBinding,
            args.signedTimestamp,
            args.webPoSignalOutput,
            args.skipPrivacyBuffer
        ]);
    }
}

// ---- dist/core/ChallengeFetcher.js ----
/**
 * Fetches a BotGuard challenge using the provided configuration.
 * @NOTE
 * For YouTube specifically, you may need to fetch it using InnerTube instead
 * depending on the client.
 */
async function getChallenge(config) {
    const { requestKey, interpreterHash, fetchFunction, useYouTubeAPI } = config;
    if (!fetchFunction)
        throw new BgError('No fetch function provided');
    if (!requestKey)
        throw new BgError('No request key provided');
    const payload = [requestKey];
    if (interpreterHash)
        payload.push(interpreterHash);
    const response = await fetchFunction(buildURL('Create', useYouTubeAPI), {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify(payload)
    });
    if (!response.ok)
        throw new BgError('Failed to fetch challenge', { status: response.status });
    const rawData = await response.json();
    return parseChallengeData(rawData);
}
/**
 * Parses the challenge data from the provided response data.
 */
function parseChallengeData(rawData) {
    let challengeData = [];
    if (rawData.length > 1 && typeof rawData[1] === 'string') {
        const descrambled = descrambleChallenge(rawData[1]);
        challengeData = JSON.parse(descrambled || '[]');
    }
    else if (rawData.length && typeof rawData[0] === 'object') {
        challengeData = rawData[0];
    }
    const [messageId, wrappedScript, wrappedUrl, interpreterHash, program, globalName, , clientExperimentsStateBlob] = challengeData;
    const privateDoNotAccessOrElseSafeScriptWrappedValue = Array.isArray(wrappedScript) ? wrappedScript.find((value) => value && typeof value === 'string') : undefined;
    const privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = Array.isArray(wrappedUrl) ? wrappedUrl.find((value) => value && typeof value === 'string') : undefined;
    const clientSideBgChallenge = {
        messageId,
        interpreterHash,
        program,
        globalName,
        clientExperimentsStateBlob
    };
    if (privateDoNotAccessOrElseSafeScriptWrappedValue) {
        clientSideBgChallenge.interpreterJavascript = {
            privateDoNotAccessOrElseSafeScriptWrappedValue
        };
    }
    if (privateDoNotAccessOrElseTrustedResourceUrlWrappedValue) {
        clientSideBgChallenge.interpreterUrl = {
            privateDoNotAccessOrElseTrustedResourceUrlWrappedValue
        };
    }
    return clientSideBgChallenge;
}
/**
 * Descrambles the given challenge data.
 */
function descrambleChallenge(scrambledChallenge) {
    const buffer = base64ToU8(scrambledChallenge);
    if (buffer.length)
        return new TextDecoder().decode(buffer.map((b) => b + 97));
}

// ---- dist/core/WebPoMinter.js ----
/**
 * Represents a Web Proof of Origin Token minter.
 */
class WebPoMinter {
    mintCallback;
    constructor(mintCallback) {
        this.mintCallback = mintCallback;
    }
    /**
     * Factory method to create a WebPoMinter instance.
     * @param integrityTokenResponse - The integrity token response object.
     * @param webPoSignalOutput - The output array containing the minter function.
     */
    static async create(integrityTokenResponse, webPoSignalOutput) {
        const getMinter = webPoSignalOutput[0];
        if (!getMinter)
            throw new BgError('PMD:Undefined');
        if (!integrityTokenResponse.integrityToken)
            throw new BgError('No integrity token provided', { integrityTokenResponse });
        const mintCallback = await getMinter(base64ToU8(integrityTokenResponse.integrityToken));
        if (!(mintCallback instanceof Function))
            throw new BgError('APF:Failed');
        return new WebPoMinter(mintCallback);
    }
    /**
     * Mints a proof and returns it as a web-safe base64 string.
     * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
     */
    async mintAsWebsafeString(contentBinding) {
        return u8ToBase64(await this.mint(contentBinding), true);
    }
    /**
     * Mints a proof and returns it as a Uint8Array.
     * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
     */
    async mint(contentBinding) {
        const result = await this.mintCallback(new TextEncoder().encode(contentBinding));
        if (!result)
            throw new BgError('YNJ:Undefined');
        if (!(result instanceof Uint8Array))
            throw new BgError('ODM:Invalid');
        return result;
    }
}
/**
 * Creates a cold start token. This can be used while `sps` (StreamProtectionStatus) is 2, but will not work once it changes to 3.
 * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
 * @param clientState - An integer representing the client state. Defaults to 1.
 */
function createColdStartToken(contentBinding, clientState) {
    const contentBindingBytes = new TextEncoder().encode(contentBinding);
    const timestamp = Math.floor(Date.now() / 1000);
    const randomKeys = [Math.floor(Math.random() * 256), Math.floor(Math.random() * 256)];
    // NOTE: The "0" value before the client state is supposed to be someVal & 0xFF.
    // It is always 0 though, so I didn't bother investigating further.
    const header = randomKeys.concat([
        0, (clientState ?? 1)
    ], [
        (timestamp >> 24) & 0xFF,
        (timestamp >> 16) & 0xFF,
        (timestamp >> 8) & 0xFF,
        timestamp & 0xFF
    ]);
    const packet = new Uint8Array(2 + header.length + contentBindingBytes.length);
    packet[0] = 34;
    packet[1] = header.length + contentBindingBytes.length;
    packet.set(header, 2);
    packet.set(contentBindingBytes, 2 + header.length);
    const payload = packet.subarray(2);
    const keyLength = randomKeys.length;
    for (let i = keyLength; i < payload.length; i++) {
        payload[i] ^= payload[i % keyLength];
    }
    return u8ToBase64(packet, true);
}
;
/**
 * Decodes a cold start token.
 * @param token - The cold start token to decode.
 */
function decodeColdStartToken(token) {
    const packet = base64ToU8(token);
    const payloadLength = packet[1];
    const totalPacketLength = 2 + payloadLength;
    if (packet.length !== totalPacketLength)
        throw new BgError('Invalid packet length.', { packetLength: packet.length, expectedLength: totalPacketLength });
    const payload = packet.subarray(2);
    // Decrypt the payload by reversing the XOR operation.
    const keyLength = 2;
    for (let i = keyLength; i < payload.length; ++i) {
        payload[i] ^= payload[i % keyLength];
    }
    const keys = [payload[0], payload[1]];
    const unknownVal = payload[2]; // This is the masked prop mentioned in the function above.
    const clientState = payload[3];
    const timestamp = (payload[4] << 24) |
        (payload[5] << 16) |
        (payload[6] << 8) |
        payload[7];
    const date = new Date(timestamp * 1000);
    const contentBinding = new TextDecoder().decode(payload.subarray(8));
    return {
        contentBinding,
        timestamp,
        unknownVal,
        clientState,
        keys,
        date
    };
}

window.VidChainBg = { getChallenge, BotGuardClient, WebPoMinter, buildURL, getHeaders, base64ToU8, u8ToBase64 };
})();

package com.freeadbremote;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Professional ADB Client Implementation - Optimized for File Transfer
 * Uses Direct ADB SYNC Protocol for reliable binary file transfers
 */
public class AdbClient {
    // ADB Protocol Constants
    private static final int AUTH = 0x48545541; // "AUTH"
    private static final int CNXN = 0x4e584e43; // "CNXN"
    private static final int OPEN = 0x4e45504f; // "OPEN"
    private static final int OKAY = 0x59414b4f; // "OKAY"
    private static final int WRTE = 0x45545257; // "WRTE"
    private static final int CLSE = 0x45534c43; // "CLSE"
    private static final String SYNC_SEND = "SEND";
    private static final String SYNC_DATA = "DATA";
    private static final String SYNC_DONE = "DONE";
    private static final int SYNC_DATA_MAX = 65536; // 64KB max data chunk size
    // AUTH types
    private static final int AUTH_TYPE_TOKEN = 1;
    private static final int AUTH_TYPE_SIGNATURE = 2;
    private static final int AUTH_TYPE_RSAPUBLICKEY = 3;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 10000;
    private static final int MAX_PAYLOAD_SIZE = 16777216; // 16MB
    private final Context context;
    private final LogManager logManager;
    private final ExecutorService executor;
    private final AdbKeyManager keyManager;
    private final File keysDir;
    private final File trustFile;
    private String lastLoggedFingerprint = null;
    private Socket adbSocket;
    private InputStream adbInputStream;
    private OutputStream adbOutputStream;
    private boolean isConnected = false;
    private String connectedHost;
    private int connectedPort;
    private LinkedBlockingQueue<byte[]> commandQueue;
    private Thread writeThread;
    private AdbStream shellStream;
    private final ConcurrentHashMap<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private int nextStreamId = 1;
    private volatile AdbStream pendingStream = null;

    public AdbClient(Context context) {
        this.context = context.getApplicationContext();
        this.logManager = LogManager.getInstance(context);
        this.executor = Executors.newCachedThreadPool();
        this.keyManager = new AdbKeyManager(context);
        this.keysDir = new File(context.getFilesDir(), "adb_keys");
        if (!keysDir.exists()) {
            keysDir.mkdirs();
        }
        this.trustFile = new File(keysDir, "trust_established");
        if (keyManager.ensureKeysExist()) {
            String fingerprint = keyManager.getKeyFingerprint();
            logManager.logInfo("AdbClient initialized - Key fingerprint: " + fingerprint);
        } else {
            logManager.logError("Failed to initialize ADB keys", new Exception("Key initialization failed"));
        }
    }

    public boolean isTrustEstablished() {
        return trustFile.exists();
    }

    private void handleAuth(AdbMessage authMsg, boolean forcePublicKey) throws Exception {
        logManager.logInfo("Handling AUTH message, arg0=" + authMsg.arg0 + ", forcePublicKey=" + forcePublicKey);

        KeyPair keyPair = getOrCreateKeyPair();

        if (authMsg.arg0 == AUTH_TYPE_TOKEN) {
            if (forcePublicKey) {
                logManager.logInfo("Forcing public key send");
                byte[] publicKeyData = getPublicKeyForAdb(keyPair.getPublic());
                byte[] authResponse = buildAdbMessage(AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, publicKeyData);
                synchronized (adbOutputStream) {
                    adbOutputStream.write(authResponse);
                    adbOutputStream.flush();
                }
                logManager.logInfo("Sent AUTH with public key (type 3)");
            } else {
                logManager.logInfo("Signing token with private key");

                if (authMsg.data == null || authMsg.data.length == 0) {
                    throw new IOException("AUTH token is empty");
                }

                int keySize = ((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()).getModulus().bitLength()
                        / 8;

                byte[] token = authMsg.data;
                if (token.length != 20) {
                    byte[] normalizedToken = new byte[20];
                    System.arraycopy(token, 0, normalizedToken, 0, Math.min(token.length, 20));
                    token = normalizedToken;
                }

                byte[] dataToSign = new byte[keySize];
                int pos = 0;
                dataToSign[pos++] = 0x00;
                dataToSign[pos++] = 0x01;

                int paddingSize = keySize - 38;
                for (int i = 0; i < paddingSize; i++) {
                    dataToSign[pos++] = (byte) 0xFF;
                }

                dataToSign[pos++] = 0x00;

                byte[] sha1OID = new byte[] {
                        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
                };
                System.arraycopy(sha1OID, 0, dataToSign, pos, sha1OID.length);
                pos += sha1OID.length;
                System.arraycopy(token, 0, dataToSign, pos, token.length);

                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keyPair.getPrivate());
                byte[] signedToken = cipher.doFinal(dataToSign);

                byte[] authResponse = buildAdbMessage(AUTH, AUTH_TYPE_SIGNATURE, 0, signedToken);
                synchronized (adbOutputStream) {
                    adbOutputStream.write(authResponse);
                    adbOutputStream.flush();
                }

                logManager.logInfo("Sent AUTH with signed token (type 2)");
            }
        } else {
            logManager.logInfo("Sending public key (unknown AUTH type)");
            byte[] publicKeyData = getPublicKeyForAdb(keyPair.getPublic());
            byte[] authResponse = buildAdbMessage(AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, publicKeyData);
            synchronized (adbOutputStream) {
                adbOutputStream.write(authResponse);
                adbOutputStream.flush();
            }
            logManager.logInfo("Sent AUTH with public key");
        }
    }

    private void handleAuth(AdbMessage authMsg) throws Exception {
        handleAuth(authMsg, false);
    }

    private String getPublicKeyBase64(PublicKey publicKey) throws Exception {
        byte[] adbKey = getPublicKeyForAdb(publicKey);
        String fullKey = new String(adbKey, StandardCharsets.UTF_8);

        int spaceIndex = fullKey.indexOf(' ');
        if (spaceIndex > 0) {
            return fullKey.substring(0, spaceIndex);
        } else {
            String cleaned = fullKey.replace("\0", "").trim();
            if (cleaned.contains(" ")) {
                cleaned = cleaned.split(" ")[0];
            }
            return cleaned;
        }
    }

    private byte[] getPublicKeyForAdb(PublicKey publicKey) throws Exception {
        try {
            return keyManager.getPublicKeyAdb();
        } catch (IOException e) {
            logManager.logWarn("ADB-format public key not found, generating: " + e.getMessage());

            RSAPublicKey rsaKey = (RSAPublicKey) publicKey;
            java.math.BigInteger modulus = rsaKey.getModulus();
            java.math.BigInteger exponent = rsaKey.getPublicExponent();

            java.math.BigInteger zero = java.math.BigInteger.ZERO;
            java.math.BigInteger bit32 = zero.setBit(32);
            java.math.BigInteger bit2048 = zero.setBit(2048);
            java.math.BigInteger modulusSquared = modulus.multiply(modulus);
            java.math.BigInteger modPow = bit2048.modPow(java.math.BigInteger.valueOf(2), modulusSquared);
            java.math.BigInteger modInverse = modulus.remainder(bit32).modInverse(bit32);

            int[] modulusParts = new int[64];
            int[] modPowParts = new int[64];
            java.math.BigInteger currentModulus = modulus;
            java.math.BigInteger currentModPow = modPow;

            for (int i = 0; i < 64; i++) {
                java.math.BigInteger[] modPowDiv = currentModPow.divideAndRemainder(bit32);
                currentModPow = modPowDiv[0];
                modPowParts[i] = modPowDiv[1].intValue();

                java.math.BigInteger[] modulusDiv = currentModulus.divideAndRemainder(bit32);
                currentModulus = modulusDiv[0];
                modulusParts[i] = modulusDiv[1].intValue();
            }

            ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(64);
            buffer.putInt(modInverse.negate().intValue());

            for (int i = 0; i < 64; i++) {
                buffer.putInt(modulusParts[i]);
            }
            for (int i = 0; i < 64; i++) {
                buffer.putInt(modPowParts[i]);
            }

            buffer.putInt(exponent.intValue());
            byte[] keyData = buffer.array();

            String base64Key = android.util.Base64.encodeToString(keyData, android.util.Base64.NO_WRAP);
            String fullKey = base64Key + " unknown@unknown\0";
            byte[] adbFormatKey = fullKey.getBytes(StandardCharsets.UTF_8);

            try {
                File adbKeyFile = new File(keysDir, "adbkey.pub.adb");
                try (FileOutputStream fos = new FileOutputStream(adbKeyFile)) {
                    fos.write(adbFormatKey);
                    fos.flush();
                }
                logManager.logInfo("Saved ADB-format public key");
            } catch (Exception saveEx) {
                logManager.logWarn("Could not save ADB-format key: " + saveEx.getMessage());
            }

            return adbFormatKey;
        }
    }

    private KeyPair getOrCreateKeyPair() throws Exception {
        if (!keyManager.ensureKeysExist()) {
            throw new Exception("Failed to ensure ADB keys exist");
        }

        KeyPair keyPair = keyManager.getKeyPair();
        String fingerprint = keyManager.getKeyFingerprint();

        if (lastLoggedFingerprint != null && !lastLoggedFingerprint.equals(fingerprint)) {
            logManager.logError("FINGERPRINT CHANGED! Previous: " + lastLoggedFingerprint + ", Current: " + fingerprint,
                    new Exception("Key fingerprint changed"));
        }
        lastLoggedFingerprint = fingerprint;

        return keyPair;
    }

    private byte[] buildAdbMessage(int command, int arg0, int arg1, byte[] data) {
        if (data != null && data.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload too large: " + data.length + " bytes");
        }

        int dataLength = data != null ? data.length : 0;
        int totalSize = 24 + dataLength;

        ByteBuffer buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(command);
        buffer.putInt(arg0);
        buffer.putInt(arg1);
        buffer.putInt(dataLength);

        int checksum = 0;
        if (data != null) {
            for (byte b : data) {
                checksum += (b & 0xFF);
            }
        }
        buffer.putInt(checksum);
        buffer.putInt(~command);

        if (data != null && dataLength > 0) {
            buffer.put(data);
        }

        return buffer.array();
    }

    private AdbMessage readAdbMessage() throws IOException {
        byte[] header = new byte[24];
        int bytesRead = 0;
        while (bytesRead < 24) {
            int read = adbInputStream.read(header, bytesRead, 24 - bytesRead);
            if (read < 0) {
                throw new IOException("Stream closed while reading header");
            }
            bytesRead += read;
        }

        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        AdbMessage msg = new AdbMessage();
        msg.command = buffer.getInt();
        msg.arg0 = buffer.getInt();
        msg.arg1 = buffer.getInt();
        int dataLength = buffer.getInt();
        int checksum = buffer.getInt();
        int magic = buffer.getInt();

        if (magic != ~msg.command) {
            throw new IOException("Invalid ADB message: magic mismatch");
        }

        if (dataLength > 0) {
            if (dataLength > MAX_PAYLOAD_SIZE) {
                throw new IOException("Payload too large: " + dataLength + " bytes");
            }
            msg.data = new byte[dataLength];
            bytesRead = 0;
            while (bytesRead < dataLength) {
                int read = adbInputStream.read(msg.data, bytesRead, dataLength - bytesRead);
                if (read < 0) {
                    throw new IOException("Stream closed while reading payload");
                }
                bytesRead += read;
            }

            int calculatedChecksum = 0;
            for (byte b : msg.data) {
                calculatedChecksum += (b & 0xFF);
            }
            if (calculatedChecksum != checksum) {
                throw new IOException("Invalid ADB message: checksum mismatch");
            }
        }

        return msg;
    }

    public void connect(String host, int port, ConnectionCallback callback) {
        if (adbSocket != null || isConnected) {
            logManager.logInfo("Closing previous connection before reconnecting");
            closeConnection();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        executor.execute(() -> {
            try {
                logManager.logInfo("Connecting to ADB server at " + host + ":" + port);

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30000);

                this.adbSocket = socket;
                this.adbInputStream = socket.getInputStream();
                this.adbOutputStream = socket.getOutputStream();
                this.connectedHost = host;
                this.connectedPort = port;

                logManager.logInfo("Socket connected, starting ADB handshake");

                byte[] connectData = "host::\0".getBytes(StandardCharsets.UTF_8);
                byte[] cnxnMessage = buildAdbMessage(CNXN, 16777216, 4096, connectData);

                synchronized (adbOutputStream) {
                    adbOutputStream.write(cnxnMessage);
                    adbOutputStream.flush();
                }

                logManager.logInfo("Sent ADB CNXN message, waiting for response...");

                socket.setSoTimeout(30000);
                AdbMessage response = readAdbMessage();
                logManager.logInfo(String.format("Received ADB response: cmd=0x%08x arg0=0x%08x arg1=0x%08x",
                        response.command, response.arg0, response.arg1));

                int authAttempts = 0;
                int maxAuthAttempts = 5;
                boolean triedPublicKey = false;
                while (response.command == AUTH && authAttempts < maxAuthAttempts) {
                    logManager.logInfo("Server requires authentication (attempt " + (authAttempts + 1) + ")");

                    if (authAttempts == 0) {
                        logManager.logInfo("First auth attempt - trying signed token");
                        handleAuth(response, false);
                    } else if (authAttempts >= 1 && !triedPublicKey) {
                        logManager.logInfo("Signed token failed - sending public key...");
                        handleAuth(response, true);
                        triedPublicKey = true;
                    } else {
                        handleAuth(response, triedPublicKey);
                    }
                    authAttempts++;

                    response = readAdbMessage();
                    logManager.logInfo(
                            String.format("Received ADB response after AUTH: cmd=0x%08x arg0=0x%08x arg1=0x%08x",
                                    response.command, response.arg0, response.arg1));

                    if (response.command == CNXN) {
                        break;
                    }

                    if (response.command == AUTH) {
                        logManager.logWarn("Server sent another AUTH message, retrying...");
                        continue;
                    }
                }

                if (response.command != CNXN) {
                    String error = response.data != null ? new String(response.data, StandardCharsets.UTF_8)
                            : "Unexpected response";
                    throw new IOException(
                            "Expected CNXN response, got: 0x" + Integer.toHexString(response.command) + " - " + error);
                }

                logManager.logInfo("ADB handshake complete");
                socket.setSoTimeout(0);

                startResponseReader();
                openShellStream();

                isConnected = true;

                if (triedPublicKey) {
                    logManager.logInfo("Public key was sent and accepted");

                    if (!isTrustEstablished()) {
                        trustFile.createNewFile();
                        logManager.logInfo("Trust file created");
                    }

                    Thread.sleep(2000);

                    if (shellStream != null && shellStream.isReady) {
                        KeyPair keyPair = getOrCreateKeyPair();
                        writePublicKeyToDevice(keyPair.getPublic());
                    }
                } else {
                    logManager.logInfo("Device recognized our key");

                    if (!isTrustEstablished()) {
                        trustFile.createNewFile();
                    }

                    Thread.sleep(1000);
                    if (shellStream != null && shellStream.isReady) {
                        KeyPair keyPair = getOrCreateKeyPair();
                        String base64Key = getPublicKeyBase64(keyPair.getPublic());
                        String verifyCmd = "grep -q '" + base64Key.substring(0, Math.min(40, base64Key.length()))
                                + "' /data/misc/adb/adb_keys 2>/dev/null && echo EXISTS || echo MISSING";
                        final boolean[] keyExistsOnDevice = { false };
                        executeCommand(verifyCmd, new CommandCallback() {
                            @Override
                            public void onOutput(String output) {
                                if (output != null && output.contains("EXISTS")) {
                                    keyExistsOnDevice[0] = true;
                                }
                            }

                            @Override
                            public void onError(String error) {
                            }

                            @Override
                            public void onComplete(int exitCode) {
                            }
                        });
                        Thread.sleep(500);

                        if (!keyExistsOnDevice[0]) {
                            logManager.logInfo("Key not found on device - writing it now");
                            writePublicKeyToDevice(keyPair.getPublic());
                        } else {
                            logManager.logInfo("Key verified on device");
                        }
                    }
                }

                if (callback != null) {
                    callback.onConnected();
                }

                logManager.logInfo("ADB connection established successfully");

            } catch (Exception e) {
                logManager.logError("Failed to connect to ADB server", e);
                closeConnection();
                if (callback != null) {
                    callback.onError(e);
                }
            }
        });
    }

    private void openShellStream() throws IOException, InterruptedException {
        // Ensure unique stream ID
        synchronized (this) {
            while (streams.containsKey(nextStreamId)) {
                nextStreamId++;
                if (nextStreamId > 1000) nextStreamId = 1; // Reset if needed
            }
        }
        int streamId = nextStreamId++;
        logManager.logInfo("Opening shell stream with ID: " + streamId);

        shellStream = new AdbStream(streamId);
        pendingStream = shellStream;
        commandQueue = new LinkedBlockingQueue<>();

        ByteBuffer shellBuffer = ByteBuffer.allocate(7);
        shellBuffer.put("shell:".getBytes(StandardCharsets.UTF_8));
        shellBuffer.put((byte) 0);
        byte[] shellCommand = shellBuffer.array();

        byte[] openMessage = buildAdbMessage(OPEN, streamId, 0, shellCommand);

        synchronized (adbOutputStream) {
            adbOutputStream.write(openMessage);
            adbOutputStream.flush();
        }

        long startTime = System.currentTimeMillis();
        long timeout = 10000;

        synchronized (shellStream) {
            while (!shellStream.isReady && (System.currentTimeMillis() - startTime) < timeout) {
                long remaining = timeout - (System.currentTimeMillis() - startTime);
                if (remaining > 0) {
                    shellStream.wait(Math.min(remaining, 1000));
                } else {
                    break;
                }
            }
        }

        if (!shellStream.isReady) {
            streams.remove(streamId);
            shellStream = null;
            throw new IOException("Shell stream not opened");
        }

        logManager.logInfo("Shell stream opened and ready (remoteId=" + shellStream.remoteId + ")");
        shellStream.writeReady.set(true);
        startWriteThread();
    }

    private void startResponseReader() {
        Thread responseThread = new Thread(() -> {
            try {
                logManager.logInfo("Response reader thread started");
                while (adbSocket != null && !adbSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                    try {
                        AdbMessage msg = readAdbMessage();

                        if (msg.command == OKAY) {
                            AdbStream stream = streams.get(msg.arg1);
                            if (stream == null && pendingStream != null) {
                                logManager.logInfo("Using pending stream, server arg1=" + msg.arg1);
                                stream = pendingStream;
                                streams.put(msg.arg1, stream);
                                pendingStream = null;
                            }
                            if (stream != null) {
                                stream.remoteId = msg.arg0;
                                stream.writeReady.set(true);
                                stream.setReady(true);
                                synchronized (stream) {
                                    stream.notifyAll();
                                }
                                if (stream.streamId > 1) {
                                    logManager.logInfo("OKAY received: streamId=" + stream.streamId + ", remoteId="
                                            + stream.remoteId);
                                }
                            } else {
                                logManager.logWarn(
                                        "Received OKAY for unknown stream: arg0=" + msg.arg0 + ", arg1=" + msg.arg1);
                            }
                        } else if (msg.command == WRTE) {
                            AdbStream stream = streams.get(msg.arg1);
                            if (stream != null) {
                                if (msg.data != null && msg.data.length > 0) {
                                    // Check for SYNC FAIL messages (ADB SYNC protocol)
                                    // FAIL messages start with "FAIL" followed by error message
                                    if (msg.data.length >= 4) {
                                        String dataStr = new String(msg.data, 0, Math.min(4, msg.data.length), StandardCharsets.UTF_8);
                                        if ("FAIL".equals(dataStr)) {
                                            String errorMsg = msg.data.length > 4 ? 
                                                new String(msg.data, 4, msg.data.length - 4, StandardCharsets.UTF_8).trim() : 
                                                "Unknown error";
                                            logManager.logError("ADB SYNC FAIL received: " + errorMsg, null);
                                            // Mark stream as failed
                                            stream.syncError = errorMsg;
                                            stream.syncFailed = true;
                                            synchronized (stream) {
                                                stream.notifyAll();
                                            }
                                        }
                                    }
                                    stream.addData(msg.data);
                                }
                                byte[] okayMsg = buildAdbMessage(OKAY, stream.streamId, stream.remoteId, null);
                                synchronized (adbOutputStream) {
                                    adbOutputStream.write(okayMsg);
                                    adbOutputStream.flush();
                                }
                            } else {
                                logManager.logWarn("Received WRTE for unknown stream: arg1=" + msg.arg1);
                            }
                        } else if (msg.command == CLSE) {
                            logManager.logDebug("Received CLSE: arg0=" + msg.arg0 + ", arg1=" + msg.arg1);
                            AdbStream stream = streams.get(msg.arg1);
                            if (stream != null) {
                                streams.remove(msg.arg1);
                                stream.close();
                            }
                        } else {
                            logManager.logWarn("Unexpected ADB message: 0x" + Integer.toHexString(msg.command));
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    } catch (java.io.IOException e) {
                        String errorMsg = e.getMessage();
                        if (errorMsg == null)
                            errorMsg = "";

                        boolean isConnectionLost = adbSocket == null ||
                                adbSocket.isClosed() ||
                                !adbSocket.isConnected() ||
                                errorMsg.contains("Stream closed") ||
                                errorMsg.contains("Broken pipe") ||
                                errorMsg.contains("Connection reset");

                        if (isConnectionLost) {
                            isConnected = false;
                            logManager.logInfo("Connection lost - exiting response reader");
                            break;
                        }

                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                }
            } catch (Exception e) {
                logManager.logError("Fatal error in response reader", e);
            } finally {
                isConnected = false;
                logManager.logInfo("Response reader thread exited");
            }
        });
        responseThread.setName("AdbClient-ResponseReader");
        responseThread.setDaemon(true);
        responseThread.start();
    }

    private void startWriteThread() {
        if (writeThread != null && writeThread.isAlive()) {
            return;
        }

        writeThread = new Thread(() -> {
            try {
                logManager.logInfo("Write thread started");
                while (adbSocket != null && !adbSocket.isClosed() &&
                        shellStream != null && !shellStream.isClosed &&
                        !Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] command = commandQueue.take();

                        if (command != null && shellStream != null && !shellStream.isClosed) {
                            if (adbSocket != null && !adbSocket.isClosed() && adbSocket.isConnected()) {
                                byte[] wrteMessage = buildAdbMessage(WRTE, shellStream.streamId, shellStream.remoteId,
                                        command);

                                synchronized (adbOutputStream) {
                                    adbOutputStream.write(wrteMessage);
                                    adbOutputStream.flush();
                                }

                                synchronized (shellStream) {
                                    long waitStart = System.currentTimeMillis();
                                    long waitTimeout = 5000;
                                    while (!shellStream.writeReady.get()
                                            && (System.currentTimeMillis() - waitStart) < waitTimeout) {
                                        try {
                                            shellStream.wait(100);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                    }
                                    shellStream.writeReady.set(false);
                                }
                            } else {
                                logManager.logWarn("Socket closed, dropping command");
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                logManager.logError("Fatal error in write thread", e);
            } finally {
                logManager.logInfo("Write thread exiting");
            }
        });
        writeThread.setName("AdbClient-WriteThread");
        writeThread.setDaemon(true);
        writeThread.start();
    }

    private void writePublicKeyToDevice(PublicKey publicKey) {
        executor.execute(() -> {
            try {
                Thread.sleep(2000);

                if (!isConnected || shellStream == null || shellStream.isClosed || !shellStream.isReady) {
                    return;
                }

                String base64Key = getPublicKeyBase64(publicKey);
                String[] keyPaths = {
                        "/data/misc/adb/adb_keys",
                        "/adb_keys",
                        "/sdcard/adb_keys"
                };

                final boolean[] written = { false };
                for (String keyPath : keyPaths) {
                    try {
                        String checkCmd = "grep -q '" + base64Key.substring(0, Math.min(20, base64Key.length())) + "' "
                                + keyPath + " 2>&1";
                        final boolean[] keyExists = { false };
                        executeCommand(checkCmd, new CommandCallback() {
                            @Override
                            public void onOutput(String output) {
                            }

                            @Override
                            public void onError(String error) {
                            }

                            @Override
                            public void onComplete(int exitCode) {
                                keyExists[0] = (exitCode == 0);
                            }
                        });
                        Thread.sleep(500);

                        if (keyExists[0]) {
                            written[0] = true;
                            break;
                        }

                        String keyLine = base64Key + " unknown@unknown\n";
                        String escapedKeyLine = keyLine.replace("'", "'\\''");
                        String writeCmd = "echo '" + escapedKeyLine.trim() + "' >> " + keyPath + " 2>&1";

                        final boolean[] writeSuccess = { false };
                        executeCommand(writeCmd, new CommandCallback() {
                            @Override
                            public void onOutput(String output) {
                                if (output != null && !output.contains("Permission denied")) {
                                    writeSuccess[0] = true;
                                }
                            }

                            @Override
                            public void onError(String error) {
                            }

                            @Override
                            public void onComplete(int exitCode) {
                                if (writeSuccess[0] || exitCode == 0) {
                                    logManager.logInfo("Successfully wrote public key to " + keyPath);
                                    written[0] = true;
                                }
                            }
                        });

                        Thread.sleep(1500);

                        if (written[0]) {
                            break;
                        }
                    } catch (Exception e) {
                        logManager.logDebug("Error writing to " + keyPath + ": " + e.getMessage());
                    }
                }

                if (!written[0]) {
                    logManager.logWarn("Could not write public key to device");
                }
            } catch (Exception e) {
                logManager.logError("Error writing public key to device", e);
            }
        });
    }

    public void executeCommand(String command, CommandCallback callback) {
        if (!isConnected || !checkConnectionHealth()) {
            String error = "Not connected to ADB server";
            if (callback != null) {
                callback.onError(error);
            }
            isConnected = false;
            return;
        }

        if (shellStream == null || shellStream.isClosed || !shellStream.isReady) {
            String error = "Shell stream not ready";
            if (callback != null) {
                callback.onError(error);
            }
            if (shellStream != null && shellStream.isClosed) {
                isConnected = false;
            }
            return;
        }

        if (adbSocket == null || adbSocket.isClosed() || !adbSocket.isConnected()) {
            String error = "Socket closed - connection lost";
            isConnected = false;
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }

        executor.execute(() -> {
            try {
                if (shellStream != null) {
                    synchronized (shellStream.dataQueue) {
                        shellStream.dataQueue.clear();
                    }
                }

                // Small delay to ensure previous command output is processed
                Thread.sleep(50);

                String commandWithNewline = command.endsWith("\n") ? command : command + "\n";
                byte[] commandBytes = commandWithNewline.getBytes(StandardCharsets.UTF_8);
                commandQueue.add(commandBytes);

                StringBuilder output = new StringBuilder();
                long startTime = System.currentTimeMillis();
                long timeout = 5000;
                long lastDataTime = startTime;
                boolean hasReceivedData = false;

                while (System.currentTimeMillis() - startTime < timeout) {
                    try {
                        byte[] data = shellStream.readDataNonBlocking();
                        if (data == null) {
                            long elapsed = System.currentTimeMillis() - lastDataTime;
                            if (hasReceivedData && elapsed > 100) {
                                break;
                            }
                            Thread.sleep(10);
                            continue;
                        }

                        hasReceivedData = true;
                        lastDataTime = System.currentTimeMillis();
                        String chunk = new String(data, StandardCharsets.UTF_8);
                        output.append(chunk);
                        if (callback != null) {
                            callback.onOutput(chunk);
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        if (e instanceof IOException) {
                            logManager.logWarn("Stream closed while reading output");
                        }
                        break;
                    }
                }

                if (callback != null) {
                    callback.onComplete(0);
                }

            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * OPTIMIZED: Push file using Direct ADB SYNC Protocol
     * This is the PRIMARY and ONLY file transfer method
     */
    public void pushFile(File localFile, String remotePath, CommandCallback callback) {
        logManager.logInfo("pushFile() called: " + localFile.getAbsolutePath() + " → " + remotePath);
        
        if (localFile == null || !localFile.exists()) {
            String error = "Local file does not exist: " + (localFile != null ? localFile.getAbsolutePath() : "null");
            logManager.logError(error, new Exception("File not found"));
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        if (remotePath == null || remotePath.trim().isEmpty()) {
            String error = "Remote path is null or empty";
            logManager.logError(error, new Exception("Invalid remote path"));
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        if (!isConnected || !checkConnectionHealth()) {
            String error = "Not connected to ADB server (isConnected=" + isConnected + ", health=" + checkConnectionHealth() + ")";
            logManager.logError(error, new Exception("ADB not connected"));
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        logManager.logInfo("pushFile() validation passed, starting push operation...");

        executor.execute(() -> {
            AdbStream syncStream = null;
            try {
                logManager.logInfo("=== OPTIMIZED SYNC PUSH START ===");
                logManager
                        .logInfo("Local file: " + localFile.getAbsolutePath() + " (" + localFile.length() + " bytes)");
                logManager.logInfo("Remote path: " + remotePath);

                // STEP 1: Open sync: stream
                // Ensure unique stream ID
                synchronized (this) {
                    while (streams.containsKey(nextStreamId)) {
                        nextStreamId++;
                        if (nextStreamId > 1000) nextStreamId = 1; // Reset if needed
                    }
                }
                int streamId = nextStreamId++;
                syncStream = new AdbStream(streamId);
                pendingStream = syncStream;
                streams.put(streamId, syncStream);

                String syncService = "sync:";
                byte[] serviceBytes = syncService.getBytes(StandardCharsets.UTF_8);
                byte[] openMessage = buildAdbMessage(OPEN, streamId, 0, serviceBytes);

                logManager.logInfo("Opening SYNC stream (streamId=" + streamId + ")...");
                synchronized (adbOutputStream) {
                    adbOutputStream.write(openMessage);
                    adbOutputStream.flush();
                }

                // Wait for OKAY (stream open confirmation) - improved waiting
                long startTime = System.currentTimeMillis();
                long timeout = 10000; // 10 seconds timeout
                synchronized (syncStream) {
                    while ((syncStream.remoteId == 0 || !syncStream.isReady) && 
                           (System.currentTimeMillis() - startTime) < timeout) {
                        try {
                            long remaining = timeout - (System.currentTimeMillis() - startTime);
                            if (remaining > 0) {
                                syncStream.wait(Math.min(remaining, 100));
                            } else {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for SYNC stream OKAY");
                        }
                    }
                }

                if (syncStream.remoteId == 0 || !syncStream.isReady) {
                    streams.remove(streamId);
                    throw new IOException("Failed to open sync stream - no OKAY received (remoteId=" + 
                                         syncStream.remoteId + ", isReady=" + syncStream.isReady + ")");
                }

                logManager.logInfo("SYNC stream opened successfully (streamId=" + streamId + 
                                 ", remoteId=" + syncStream.remoteId + ")");
                pendingStream = null;

                // STEP 2: Prepare file data
                FileInputStream fis = new FileInputStream(localFile);
                long fileSize = localFile.length();

                // CRITICAL FIX #3: Path handling for Rockchip devices
                String path = remotePath;
                // Convert /storage/emulated/0/ to /sdcard/ for better compatibility
                if (path.startsWith("/storage/emulated/0/")) {
                    path = path.replace("/storage/emulated/0/", "/sdcard/");
                    logManager.logInfo("Path converted: " + path);
                }
                // Remove any double slashes
                path = path.replaceAll("//+", "/");

                byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
                int permissions = 0644;

                logManager.logInfo("SEND command: path=" + path + ", size=" + fileSize + " bytes, permissions=0"
                        + Integer.toOctalString(permissions));

                // STEP 3: Send SEND command
                // ADB SYNC SEND format: "SEND" (4 bytes) + path_length (4 bytes LE) + path (N bytes) + permissions (4 bytes LE)
                // CRITICAL FIX: Ensure proper byte order and null-terminated path for Rockchip compatibility
                ByteBuffer sendBuffer = ByteBuffer.allocate(4 + 4 + pathBytes.length + 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
                sendBuffer.put(SYNC_SEND.getBytes(StandardCharsets.UTF_8)); // "SEND" (4 bytes)
                sendBuffer.order(ByteOrder.LITTLE_ENDIAN); // Ensure little-endian for integers
                sendBuffer.putInt(pathBytes.length); // Path length (4 bytes LE)
                sendBuffer.put(pathBytes); // Path bytes
                sendBuffer.putInt(permissions); // Permissions (4 bytes LE)

                byte[] sendData = sendBuffer.array();
                byte[] sendMessage = buildAdbMessage(WRTE, streamId, syncStream.remoteId, sendData);

                syncStream.writeReady.set(false);
                synchronized (adbOutputStream) {
                    adbOutputStream.write(sendMessage);
                    adbOutputStream.flush();
                }

                logManager.logInfo("SEND command sent, waiting for OKAY...");

                // Wait for OKAY after SEND - improved with proper synchronization
                long startWait = System.currentTimeMillis();
                long waitTimeout = 10000; // 10 seconds
                boolean sendOkayReceived = false;
                synchronized (syncStream) {
                    while (!syncStream.writeReady.get() && !syncStream.syncFailed && 
                           (System.currentTimeMillis() - startWait) < waitTimeout) {
                        try {
                            long remaining = waitTimeout - (System.currentTimeMillis() - startWait);
                            if (remaining > 0) {
                                syncStream.wait(Math.min(remaining, 100));
                                if (syncStream.writeReady.get()) {
                                    sendOkayReceived = true;
                                    break;
                                }
                                if (syncStream.syncFailed) {
                                    throw new IOException("ADB SYNC FAIL: " + 
                                        (syncStream.syncError != null ? syncStream.syncError : "Unknown error"));
                                }
                            } else {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for OKAY after SEND");
                        }
                    }
                }
                
                // Check for SYNC FAIL before checking OKAY
                if (syncStream.syncFailed) {
                    throw new IOException("ADB SYNC FAIL: " + 
                        (syncStream.syncError != null ? syncStream.syncError : "Unknown error"));
                }
                
                if (!sendOkayReceived && !syncStream.writeReady.get()) {
                    throw new IOException("Timeout waiting for OKAY after SEND (" + waitTimeout + "ms)");
                }

                logManager.logInfo("OKAY received after SEND - beginning data transfer");
                syncStream.writeReady.set(false); // Reset for next operation

                // STEP 4: Send file data in chunks (binary transfer)
                // Use smaller chunks for better reliability (32KB instead of 64KB)
                int chunkSize = 32768; // 32KB chunks for better compatibility
                byte[] buffer = new byte[chunkSize];
                long totalSent = 0;
                int chunkCount = 0;
                long transferStartTime = System.currentTimeMillis();

                while (totalSent < fileSize && !syncStream.syncFailed) {
                    // Check for FAIL before each chunk
                    if (syncStream.syncFailed) {
                        throw new IOException("ADB SYNC FAIL during data transfer: " + 
                            (syncStream.syncError != null ? syncStream.syncError : "Unknown error"));
                    }
                    
                    int toRead = (int) Math.min(chunkSize, fileSize - totalSent);
                    int read = fis.read(buffer, 0, toRead);
                    if (read <= 0) {
                        throw new IOException("File read failed: got " + read + " bytes, expected " + toRead);
                    }

                    // Build DATA chunk with proper binary format
                    ByteBuffer dataBuffer = ByteBuffer.allocate(4 + 4 + read)
                            .order(ByteOrder.LITTLE_ENDIAN);
                    dataBuffer.put(SYNC_DATA.getBytes(StandardCharsets.UTF_8));
                    dataBuffer.putInt(read);
                    dataBuffer.put(buffer, 0, read);

                    byte[] dataChunk = dataBuffer.array();
                    byte[] dataMessage = buildAdbMessage(WRTE, streamId, syncStream.remoteId, dataChunk);

                    // Send DATA chunks - most ADB implementations don't send OKAY for DATA chunks
                    // Only SEND and DONE get OKAY responses
                    synchronized (adbOutputStream) {
                        adbOutputStream.write(dataMessage);
                        // Flush every chunk to ensure data is sent immediately
                        adbOutputStream.flush();
                    }

                    totalSent += read;
                    chunkCount++;

                    // Check for FAIL after sending chunk
                    if (syncStream.syncFailed) {
                        throw new IOException("ADB SYNC FAIL after sending chunk " + chunkCount + ": " + 
                            (syncStream.syncError != null ? syncStream.syncError : "Unknown error"));
                    }

                    // Progress logging every 10 chunks or at completion
                    if (chunkCount % 10 == 0 || totalSent == fileSize) {
                        int progress = (int) ((totalSent * 100) / fileSize);
                        long elapsed = System.currentTimeMillis() - transferStartTime;
                        double speed = elapsed > 0 ? (totalSent / 1024.0) / (elapsed / 1000.0) : 0;
                        logManager.logInfo(String.format("Progress: %d%% (%d/%d bytes, %.1f KB/s, chunk %d)",
                                progress, totalSent, fileSize, speed, chunkCount));

                        if (callback != null) {
                            callback.onOutput(
                                    String.format("Progress: %d%% (%d/%d bytes)\n", progress, totalSent, fileSize));
                        }
                    }

                    // Small delay every 20 chunks to prevent overwhelming the device
                    if (chunkCount % 20 == 0) {
                        Thread.sleep(5);
                    }
                }

                fis.close();

                // Check for SYNC FAIL during data transfer
                if (syncStream.syncFailed) {
                    throw new IOException("ADB SYNC FAIL during data transfer: " + 
                        (syncStream.syncError != null ? syncStream.syncError : "Unknown error"));
                }

                long transferTime = System.currentTimeMillis() - transferStartTime;
                double avgSpeed = (fileSize / 1024.0) / (transferTime / 1000.0);
                logManager.logInfo(String.format("Data transfer complete: %d bytes in %d chunks (%.1f KB/s avg)",
                        totalSent, chunkCount, avgSpeed));

                // Verify all data was sent
                if (totalSent != fileSize) {
                    throw new IOException("Transfer incomplete: sent " + totalSent + " of " + fileSize + " bytes");
                }

                // Wait before sending DONE to ensure all data is written
                Thread.sleep(200);

                // STEP 5: Send DONE command
                long timestamp = System.currentTimeMillis() / 1000;
                ByteBuffer doneBuffer = ByteBuffer.allocate(4 + 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
                doneBuffer.put(SYNC_DONE.getBytes(StandardCharsets.UTF_8));
                doneBuffer.putInt((int) timestamp);

                byte[] doneData = doneBuffer.array();
                byte[] doneMessage = buildAdbMessage(WRTE, streamId, syncStream.remoteId, doneData);

                syncStream.writeReady.set(false);
                logManager.logInfo("Sending DONE command (timestamp=" + timestamp + ")...");
                synchronized (adbOutputStream) {
                    adbOutputStream.write(doneMessage);
                    adbOutputStream.flush();
                }

                // Force socket flush to ensure DONE is sent
                synchronized (adbOutputStream) {
                    adbOutputStream.flush();
                }

                logManager.logInfo("DONE command sent, waiting for final OKAY...");

                // Wait for OKAY after DONE - CRITICAL: Must wait for OKAY to confirm file was written
                startWait = System.currentTimeMillis();
                long doneWaitTimeout = 10000; // 10 seconds - increased timeout
                boolean okayReceived = false;
                synchronized (syncStream) {
                    while (!syncStream.writeReady.get() && !syncStream.syncFailed && 
                           (System.currentTimeMillis() - startWait) < doneWaitTimeout) {
                        try {
                            long remaining = doneWaitTimeout - (System.currentTimeMillis() - startWait);
                            if (remaining > 0) {
                                syncStream.wait(Math.min(remaining, 100));
                                if (syncStream.writeReady.get()) {
                                    okayReceived = true;
                                    break;
                                }
                                if (syncStream.syncFailed) {
                                    throw new IOException("ADB SYNC FAIL after DONE: " + 
                                        (syncStream.syncError != null ? syncStream.syncError : "Unknown error"));
                                }
                            } else {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // Check for FAIL before checking OKAY
                if (syncStream.syncFailed) {
                    throw new IOException("ADB SYNC FAIL after DONE: " + 
                        (syncStream.syncError != null ? syncStream.syncError : "Unknown error"));
                }

                if (okayReceived) {
                    logManager.logInfo("Final OKAY received after DONE - file write confirmed");
                } else {
                    // If no OKAY received, this might indicate failure - log warning but continue
                    logManager.logWarn("No OKAY after DONE - file may not have been written correctly");
                    // Don't fail here - let file verification catch it
                }

                // Additional wait for filesystem sync to ensure file is fully written
                Thread.sleep(1000); // Increased wait time

                long totalTime = System.currentTimeMillis() - transferStartTime;
                logManager.logInfo(String.format("=== SYNC PUSH COMPLETE: %d bytes in %.2f seconds (%.1f KB/s) ===",
                        fileSize, totalTime / 1000.0, (fileSize / 1024.0) / (totalTime / 1000.0)));

                if (callback != null) {
                    callback.onComplete(0);
                }

            } catch (Exception e) {
                logManager.logError("SYNC push failed: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError("Failed to push file: " + e.getMessage());
                }
            } finally {
                if (syncStream != null) {
                    try {
                        byte[] closeMessage = buildAdbMessage(CLSE, syncStream.streamId, syncStream.remoteId, null);
                        synchronized (adbOutputStream) {
                            adbOutputStream.write(closeMessage);
                            adbOutputStream.flush();
                        }
                        streams.remove(syncStream.streamId);
                        syncStream.isClosed = true;
                        logManager.logInfo("SYNC stream closed");
                    } catch (Exception e) {
                        logManager.logDebug("Error closing sync stream: " + e.getMessage());
                    }
                }
            }
        });
    }
    
    /**
     * Wrapper InputStream for exec streams
     */
    private class ExecInputStream extends java.io.InputStream {
        private final AdbStream stream;
        private final int streamId;
        private final java.util.concurrent.BlockingQueue<Byte> dataQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        private volatile boolean streamClosed = false;
        
        public ExecInputStream(AdbStream stream, int streamId) {
            this.stream = stream;
            this.streamId = streamId;
        }
        
        @Override
        public int read() throws IOException {
            byte[] buf = new byte[1];
            int result = read(buf, 0, 1);
            return result == -1 ? -1 : (buf[0] & 0xFF);
        }
        
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (streamClosed && dataQueue.isEmpty()) {
                return -1;
            }
            
            int readCount = 0;
            long startTime = System.currentTimeMillis();
            long timeout = 5000; // 5 second timeout
            
            while (readCount < len && (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    // Try to read from stream's data queue
                    byte[] data = stream.readDataNonBlocking();
                    if (data != null) {
                        for (byte byteValue : data) {
                            if (readCount < len) {
                                b[off + readCount] = byteValue;
                                readCount++;
                            } else {
                                dataQueue.offer(byteValue);
                            }
                        }
                    } else if (!dataQueue.isEmpty()) {
                        // Read from our queue
                        Byte byteValue = dataQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (byteValue != null) {
                            b[off + readCount] = byteValue;
                            readCount++;
                        }
                    } else if (streamClosed) {
                        break;
                    } else {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    break;
                }
            }
            
            return readCount > 0 ? readCount : -1;
        }
        
        @Override
        public int available() throws IOException {
            return dataQueue.size() + (stream.dataQueue.size());
        }
        
        public byte[] readAllBytes() throws IOException {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
        
        @Override
        public void close() throws IOException {
            streamClosed = true;
        }
    }

    public interface ConnectionCallback {
        void onConnected();

        void onError(Exception e);
    }

    public interface CommandCallback {
        void onOutput(String output);

        void onError(String error);

        void onComplete(int exitCode);
    }

    /**
     * Install APK directly using ADB protocol without pushing to device first
     * Uses ADB install service which streams the APK directly
     */
    public void installApk(File localApk, CommandCallback callback) {
        logManager.logInfo("installApk() called: " + localApk.getAbsolutePath());
        
        if (localApk == null || !localApk.exists()) {
            String error = "Local APK file does not exist: " + (localApk != null ? localApk.getAbsolutePath() : "null");
            logManager.logError(error, new Exception("APK file not found"));
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        if (!isConnected || !checkConnectionHealth()) {
            String error = "Not connected to ADB server (isConnected=" + isConnected + ", health=" + checkConnectionHealth() + ")";
            logManager.logError(error, new Exception("ADB not connected"));
            if (callback != null) {
                callback.onError(error);
            }
            return;
        }
        
        logManager.logInfo("installApk() validation passed, starting install operation...");
        
        executor.execute(() -> {
            AdbStream installStream = null;
            try {
                logManager.logInfo("=== DIRECT ADB INSTALL START ===");
                logManager.logInfo("APK file: " + localApk.getAbsolutePath() + " (" + localApk.length() + " bytes)");
                
                // Open install service stream
                int streamId = nextStreamId++;
                installStream = new AdbStream(streamId);
                pendingStream = installStream;
                streams.put(streamId, installStream);
                
                // ADB install service format: "install" or "install:-r" for replace
                String installService = "install:-r";
                byte[] serviceBytes = installService.getBytes(StandardCharsets.UTF_8);
                byte[] openMessage = buildAdbMessage(OPEN, streamId, 0, serviceBytes);
                
                logManager.logInfo("Opening INSTALL stream (streamId=" + streamId + ", service=" + installService + ")...");
                synchronized (adbOutputStream) {
                    adbOutputStream.write(openMessage);
                    adbOutputStream.flush();
                }
                
                // Wait for OKAY
                long startTime = System.currentTimeMillis();
                long timeout = 10000;
                synchronized (installStream) {
                    while ((installStream.remoteId == 0 || !installStream.isReady) && 
                           (System.currentTimeMillis() - startTime) < timeout) {
                        try {
                            long remaining = timeout - (System.currentTimeMillis() - startTime);
                            if (remaining > 0) {
                                installStream.wait(Math.min(remaining, 100));
                            } else {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while waiting for INSTALL stream OKAY");
                        }
                    }
                }
                
                if (installStream.remoteId == 0 || !installStream.isReady) {
                    streams.remove(streamId);
                    throw new IOException("Failed to open install stream - no OKAY received");
                }
                
                logManager.logInfo("INSTALL stream opened successfully (streamId=" + streamId + 
                                 ", remoteId=" + installStream.remoteId + ")");
                pendingStream = null;
                
                // Read APK file and send it
                FileInputStream fis = new FileInputStream(localApk);
                long fileSize = localApk.length();
                byte[] buffer = new byte[32768]; // 32KB chunks
                long totalSent = 0;
                int chunkCount = 0;
                long transferStartTime = System.currentTimeMillis();
                
                logManager.logInfo("Starting APK data transfer...");
                
                while (totalSent < fileSize) {
                    int toRead = (int) Math.min(buffer.length, fileSize - totalSent);
                    int read = fis.read(buffer, 0, toRead);
                    
                    if (read <= 0) {
                        throw new IOException("File read failed: got " + read + " bytes, expected " + toRead);
                    }
                    
                    // Send data chunk
                    byte[] dataMessage = buildAdbMessage(WRTE, streamId, installStream.remoteId, 
                                                        java.util.Arrays.copyOf(buffer, read));
                    
                    synchronized (adbOutputStream) {
                        adbOutputStream.write(dataMessage);
                        adbOutputStream.flush();
                    }
                    
                    totalSent += read;
                    chunkCount++;
                    
                    // Progress logging
                    if (chunkCount % 10 == 0 || totalSent == fileSize) {
                        int progress = (int) ((totalSent * 100) / fileSize);
                        logManager.logInfo(String.format("Install progress: %d%% (%d/%d bytes)", 
                                progress, totalSent, fileSize));
                        if (callback != null) {
                            callback.onOutput(String.format("Progress: %d%%\n", progress));
                        }
                    }
                }
                
                fis.close();
                logManager.logInfo("APK data transfer complete: " + totalSent + " bytes in " + chunkCount + " chunks");
                
                // Close the stream to signal end of data
                byte[] closeMessage = buildAdbMessage(CLSE, streamId, installStream.remoteId, new byte[0]);
                synchronized (adbOutputStream) {
                    adbOutputStream.write(closeMessage);
                    adbOutputStream.flush();
                }
                
                logManager.logInfo("INSTALL stream closed, waiting for response...");
                
                // Wait for response from install service
                startTime = System.currentTimeMillis();
                timeout = 60000; // 60 seconds for install
                StringBuilder response = new StringBuilder();
                boolean installComplete = false;
                boolean installSuccess = false;
                
                synchronized (installStream) {
                    while (!installComplete && (System.currentTimeMillis() - startTime) < timeout) {
                        try {
                            byte[] data = installStream.dataQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (data != null && data.length > 0) {
                                String output = new String(data, StandardCharsets.UTF_8);
                                response.append(output);
                                logManager.logInfo("Install response: " + output.trim());
                                
                                if (callback != null) {
                                    callback.onOutput(output);
                                }
                                
                                // Check for success/failure indicators
                                String outputLower = output.toLowerCase();
                                if (outputLower.contains("success") || 
                                    outputLower.contains("installed") ||
                                    output.contains("INSTALL_SUCCEEDED")) {
                                    installSuccess = true;
                                    installComplete = true;
                                } else if (outputLower.contains("failure") || 
                                          outputLower.contains("error") ||
                                          output.contains("INSTALL_FAILED")) {
                                    installSuccess = false;
                                    installComplete = true;
                                }
                            }
                            
                            if (installStream.isClosed) {
                                installComplete = true;
                            }
                            
                            if (!installComplete) {
                                installStream.wait(100);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                
                String finalResponse = response.toString().trim();
                
                if (installComplete) {
                    if (installSuccess || finalResponse.isEmpty()) {
                        // Empty response usually means success
                        logManager.logInfo("APK installation completed successfully");
                        if (callback != null) {
                            callback.onComplete(0);
                        }
                    } else {
                        String error = "Installation failed: " + finalResponse;
                        logManager.logError(error, new Exception("Install failed"));
                        if (callback != null) {
                            callback.onError(error);
                            callback.onComplete(1);
                        }
                    }
                } else {
                    String error = "Installation timed out after " + timeout + "ms";
                    logManager.logError(error, new Exception("Install timeout"));
                    if (callback != null) {
                        callback.onError(error);
                        callback.onComplete(1);
                    }
                }
                
            } catch (Exception e) {
                logManager.logError("ADB install failed: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError("Failed to install APK: " + e.getMessage());
                    callback.onComplete(1);
                }
            } finally {
                if (installStream != null) {
                    streams.remove(installStream.streamId);
                    installStream.isClosed = true;
                }
            }
        });
    }

    public boolean isConnected() {
        return this.isConnected && adbSocket != null && adbSocket.isConnected() && !adbSocket.isClosed();
    }
    
    /**
     * Open a raw stream for exec service (e.g., "exec:system/bin/sh")
     * Returns OutputStream for writing to remote shell stdin
     */
    public java.io.OutputStream openStream(String service) {
        if (!isConnected()) {
            logManager.logError("Cannot open stream: not connected", null);
            return null;
        }
        
        try {
            // Ensure unique stream ID
            synchronized (this) {
                while (streams.containsKey(nextStreamId)) {
                    nextStreamId++;
                    if (nextStreamId > 1000) nextStreamId = 1;
                }
            }
            int streamId = nextStreamId++;
            
            AdbStream execStream = new AdbStream(streamId);
            pendingStream = execStream;
            streams.put(streamId, execStream);
            
            // Build service command
            byte[] serviceBytes = service.getBytes(StandardCharsets.UTF_8);
            byte[] openMessage = buildAdbMessage(OPEN, streamId, 0, serviceBytes);
            
            synchronized (adbOutputStream) {
                adbOutputStream.write(openMessage);
                adbOutputStream.flush();
            }
            
            // Wait for OKAY
            long startTime = System.currentTimeMillis();
            long timeout = 10000;
            synchronized (execStream) {
                while (!execStream.isReady && (System.currentTimeMillis() - startTime) < timeout) {
                    try {
                        long remaining = timeout - (System.currentTimeMillis() - startTime);
                        if (remaining > 0) {
                            execStream.wait(Math.min(remaining, 1000));
                        } else {
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            if (!execStream.isReady) {
                streams.remove(streamId);
                logManager.logError("Failed to open exec stream: " + service, null);
                return null;
            }
            
            logManager.logInfo("Exec stream opened: " + service + " (streamId=" + streamId + ")");
            execStream.writeReady.set(true);
            
            // Store for input reading
            lastExecStream = execStream;
            lastExecStreamId = streamId;
            
            // Return a wrapper OutputStream that writes to this stream
            return new ExecOutputStream(execStream, streamId);
            
        } catch (Exception e) {
            logManager.logError("Error opening exec stream: " + service, e);
            return null;
        }
    }
    
    // Store the last opened exec stream for input reading
    private AdbStream lastExecStream = null;
    private int lastExecStreamId = -1;
    
    /**
     * Get InputStream for reading from the last opened exec stream
     */
    public java.io.InputStream getStreamInput() {
        if (lastExecStream == null || lastExecStreamId == -1) {
            return null;
        }
        return new ExecInputStream(lastExecStream, lastExecStreamId);
    }
    
    /**
     * Wrapper OutputStream for exec streams
     */
    private class ExecOutputStream extends java.io.OutputStream {
        private final AdbStream stream;
        private final int streamId;
        
        public ExecOutputStream(AdbStream stream, int streamId) {
            this.stream = stream;
            this.streamId = streamId;
        }
        
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (!isConnected()) {
                throw new IOException("ADB connection closed");
            }
            
            byte[] data = new byte[len];
            System.arraycopy(b, off, data, 0, len);
            byte[] message = buildAdbMessage(WRTE, streamId, stream.remoteId, data);
            
            synchronized (adbOutputStream) {
                adbOutputStream.write(message);
                adbOutputStream.flush();
            }
        }
        
        @Override
        public void flush() throws IOException {
            synchronized (adbOutputStream) {
                adbOutputStream.flush();
            }
        }
        
        @Override
        public void close() throws IOException {
            // Close the stream by sending CLSE
            try {
                byte[] closeMessage = buildAdbMessage(CLSE, streamId, stream.remoteId, null);
                synchronized (adbOutputStream) {
                    adbOutputStream.write(closeMessage);
                    adbOutputStream.flush();
                }
                streams.remove(streamId);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void closeConnection() {
        isConnected = false;
        if (writeThread != null) {
            writeThread.interrupt();
        }
        if (adbSocket != null) {
            try {
                adbSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        if (shellStream != null) {
            shellStream.isClosed = true;
        }
    }

    public void shutdown() {
        closeConnection();
        if (executor != null) {
            executor.shutdown();
        }
    }

    public String getConnectionInfo() {
        if (isConnected() && connectedHost != null) {
            return connectedHost + ":" + connectedPort;
        }
        return null;
    }

    private boolean checkConnectionHealth() {
        return isConnected() && adbSocket != null && !adbSocket.isClosed();
    }

    private static class AdbMessage {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private static class AdbStream {
        final int streamId;
        volatile boolean isClosed = false;
        volatile boolean isReady = false;
        final AtomicBoolean writeReady = new AtomicBoolean(false);
        final LinkedBlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<>();
        int remoteId = -1;
        // SYNC protocol error handling
        volatile boolean syncFailed = false;
        volatile String syncError = null;

        AdbStream(int streamId) {
            this.streamId = streamId;
        }

        byte[] readDataNonBlocking() {
            return dataQueue.poll();
        }

        void addData(byte[] data) {
            if (data != null) {
                dataQueue.offer(data);
            }
        }

        void setReady(boolean ready) {
            isReady = ready;
            writeReady.set(ready);
        }

        void close() {
            isClosed = true;
            isReady = false;
            writeReady.set(false);
        }
    }
}
package com.freeadbremote;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Manages persistent ADB RSA key pairs
 * Ensures the same keys are used across all connection sessions for one-time trust
 */
public class AdbKeyManager {
    
    private static final String TAG = "AdbKeyManager";
    private static final String KEY_DIR_NAME = "adb_keys";
    private static final String PRIVATE_KEY_FILE = "adbkey";
    private static final String PUBLIC_KEY_FILE = "adbkey.pub";
    private static final String PUBLIC_KEY_ADB_FILE = "adbkey.pub.adb";
    private static final int RSA_KEY_SIZE = 2048;
    
    private final Context context;
    private final File keyDirectory;
    private final File privateKeyFile;
    private final File publicKeyFile;
    private final File publicKeyAdbFile;
    
    public AdbKeyManager(Context context) {
        this.context = context.getApplicationContext();
        this.keyDirectory = new File(context.getFilesDir(), KEY_DIR_NAME);
        this.privateKeyFile = new File(keyDirectory, PRIVATE_KEY_FILE);
        this.publicKeyFile = new File(keyDirectory, PUBLIC_KEY_FILE);
        this.publicKeyAdbFile = new File(keyDirectory, PUBLIC_KEY_ADB_FILE);
        
        if (!keyDirectory.exists()) {
            keyDirectory.mkdirs();
        }
    }
    
    /**
     * Ensures ADB keys exist. Generates new keys if they don't exist.
     * CRITICAL: Only regenerates if private/public keys are missing, NOT if ADB format is missing
     * If ADB format is missing, it will be generated on-demand in AdbClient.getPublicKeyForAdb()
     * @return true if keys are ready, false if generation failed
     */
    public boolean ensureKeysExist() {
        try {
            // CRITICAL: Only check for private and public keys (core keys)
            // ADB format can be generated on-demand without changing the fingerprint
            if (privateKeyFile.exists() && publicKeyFile.exists()) {
                // If ADB format is missing, log a warning but don't regenerate keys
                if (!publicKeyAdbFile.exists()) {
                    Log.w(TAG, "ADB format key missing - will be generated on-demand (fingerprint unchanged)");
                } else {
                    Log.d(TAG, "All ADB key files exist");
                }
                logKeyStatus();
                return true;
            }
            
            // Only regenerate if core keys are missing
            Log.i(TAG, "Core ADB keys missing (private or public), generating new key pair...");
            generateAndSaveKeyPair();
            logKeyStatus();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to ensure keys exist", e);
            return false;
        }
    }
    
    /**
     * Reads the private key as raw bytes in PKCS#8 format
     * @return private key bytes in PKCS#8 format
     * @throws IOException if file doesn't exist or can't be read
     */
    public byte[] getPrivateKey() throws IOException {
        if (!privateKeyFile.exists()) {
            throw new IOException("Private key file does not exist: " + privateKeyFile.getAbsolutePath());
        }
        return readFileBytes(privateKeyFile);
    }
    
    /**
     * Reads the public key as raw bytes
     * @return public key bytes (X.509 encoded)
     * @throws IOException if file doesn't exist or can't be read
     */
    public byte[] getPublicKey() throws IOException {
        if (!publicKeyFile.exists()) {
            throw new IOException("Public key file does not exist: " + publicKeyFile.getAbsolutePath());
        }
        return readFileBytes(publicKeyFile);
    }
    
    /**
     * Reads the ADB-format public key (custom RSA format for ADB authentication)
     * CRITICAL: This is the format sent during AUTH and must match exactly
     * @return ADB-format public key bytes
     * @throws IOException if file doesn't exist or can't be read
     */
    public byte[] getPublicKeyAdb() throws IOException {
        if (!publicKeyAdbFile.exists()) {
            throw new IOException("ADB public key file does not exist: " + publicKeyAdbFile.getAbsolutePath());
        }
        return readFileBytes(publicKeyAdbFile);
    }
    
    /**
     * Gets the KeyPair object for use with ADB authentication
     * CRITICAL: Only regenerates if private/public keys are missing, NOT if ADB format is missing
     * @return KeyPair with private and public keys
     * @throws Exception if keys can't be loaded or generated
     */
    public KeyPair getKeyPair() throws Exception {
        // CRITICAL: Only check for private and public keys, not ADB format
        // If ADB format is missing, it will be generated on-demand in AdbClient
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            Log.i(TAG, "Private or public key missing, generating new key pair...");
            generateAndSaveKeyPair();
        }
        
        try {
            // Load private key (PKCS#8 format)
            byte[] privateKeyBytes = getPrivateKey();
            PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            java.security.PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
            
            // Load public key (X.509 format)
            byte[] publicKeyBytes = getPublicKey();
            X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
            java.security.PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);
            
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load key pair", e);
            // CRITICAL: Don't regenerate if load fails - this would change the fingerprint
            // Instead, throw the exception so the caller knows keys are corrupted
            throw new Exception("Failed to load existing key pair - keys may be corrupted", e);
        }
    }
    
    /**
     * Calculates and returns the SHA-256 fingerprint of the public key
     * This fingerprint should be IDENTICAL across all app launches
     * @return hex string of the fingerprint
     */
    public String getKeyFingerprint() {
        try {
            byte[] publicKeyBytes = getPublicKey();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKeyBytes);
            return bytesToHex(hash);
        } catch (Exception e) {
            Log.e(TAG, "Failed to calculate fingerprint", e);
            return "ERROR";
        }
    }
    
    /**
     * Logs the current status of key files for debugging
     */
    public void logKeyStatus() {
        Log.d(TAG, "=== ADB Key Status ===");
        Log.d(TAG, "Key directory: " + keyDirectory.getAbsolutePath());
        Log.d(TAG, "Private key exists: " + privateKeyFile.exists());
        if (privateKeyFile.exists()) {
            Log.d(TAG, "Private key size: " + privateKeyFile.length() + " bytes");
        }
        Log.d(TAG, "Public key (X.509) exists: " + publicKeyFile.exists());
        if (publicKeyFile.exists()) {
            Log.d(TAG, "Public key (X.509) size: " + publicKeyFile.length() + " bytes");
        }
        Log.d(TAG, "Public key (ADB format) exists: " + publicKeyAdbFile.exists());
        if (publicKeyAdbFile.exists()) {
            Log.d(TAG, "Public key (ADB format) size: " + publicKeyAdbFile.length() + " bytes");
        }
        Log.d(TAG, "Key fingerprint: " + getKeyFingerprint());
        Log.d(TAG, "=====================");
    }
    
    /**
     * Generates a new RSA key pair and saves it to files
     * CRITICAL: Saves all three formats (private PKCS#8, public X.509, public ADB format)
     */
    private void generateAndSaveKeyPair() throws Exception {
        // Generate RSA 2048-bit key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(RSA_KEY_SIZE);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        
        // Save private key in PKCS#8 format (raw encoded bytes)
        byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
        saveKeyAtomic(privateKeyFile, privateKeyBytes);
        Log.d(TAG, "Private key saved: " + privateKeyFile.getAbsolutePath());
        
        // Save public key in X.509 format (raw encoded bytes) - for Java operations
        byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
        saveKeyAtomic(publicKeyFile, publicKeyBytes);
        Log.d(TAG, "Public key (X.509) saved: " + publicKeyFile.getAbsolutePath());
        
        // CRITICAL: Save public key in ADB format (custom RSA format for ADB authentication)
        // This ensures the EXACT same format is used every time during authentication
        byte[] adbPublicKeyBytes = convertToAdbFormat((RSAPublicKey) keyPair.getPublic());
        saveKeyAtomic(publicKeyAdbFile, adbPublicKeyBytes);
        Log.d(TAG, "Public key (ADB format) saved: " + publicKeyAdbFile.getAbsolutePath());
        Log.d(TAG, "All three key files saved successfully");
    }
    
    /**
     * Converts RSA public key to ADB format (custom RSA format - Rbox style)
     * This is the EXACT format sent during ADB AUTH and must match byte-for-byte
     */
    private byte[] convertToAdbFormat(RSAPublicKey publicKey) throws Exception {
        java.math.BigInteger modulus = publicKey.getModulus();
        java.math.BigInteger exponent = publicKey.getPublicExponent();
        
        // Rbox algorithm from Q3/b.java (same as AdbClient.getPublicKeyForAdb)
        java.math.BigInteger zero = java.math.BigInteger.ZERO;
        java.math.BigInteger bit32 = zero.setBit(32); // 2^32
        java.math.BigInteger bit2048 = zero.setBit(2048); // 2^2048
        java.math.BigInteger modulusSquared = modulus.multiply(modulus);
        
        // Calculate 2^2048 mod modulus^2
        java.math.BigInteger modPow = bit2048.modPow(java.math.BigInteger.valueOf(2), modulusSquared);
        
        // Calculate modInverse
        java.math.BigInteger modInverse = modulus.remainder(bit32).modInverse(bit32);
        
        // Split into 64 int arrays (Rbox algorithm)
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
        
        // Build the key buffer (524 bytes total)
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(524).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64); // Key size
        buffer.putInt(modInverse.negate().intValue()); // Negated modInverse
        
        // Write modulus parts (64 ints = 256 bytes)
        for (int i = 0; i < 64; i++) {
            buffer.putInt(modulusParts[i]);
        }
        
        // Write modPow parts (64 ints = 256 bytes)
        for (int i = 0; i < 64; i++) {
            buffer.putInt(modPowParts[i]);
        }
        
        // Write exponent
        buffer.putInt(exponent.intValue());
        byte[] keyData = buffer.array();
        
        // Base64 encode and append " unknown@unknown\0" (Rbox format)
        String base64Key = android.util.Base64.encodeToString(keyData, android.util.Base64.NO_WRAP);
        String fullKey = base64Key + " unknown@unknown\0";
        
        return fullKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    
    /**
     * Saves key data to file using atomic write operation
     * This prevents corruption if write is interrupted
     */
    private void saveKeyAtomic(File file, byte[] data) throws IOException {
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream fos = null;
        try {
            // Write to temporary file first
            fos = new FileOutputStream(tempFile);
            fos.write(data);
            fos.flush();
            fos.close();
            fos = null;
            
            // Set proper file permissions (owner read/write only)
            tempFile.setReadable(false, false);
            tempFile.setReadable(true, true);
            tempFile.setWritable(false, false);
            tempFile.setWritable(true, true);
            tempFile.setExecutable(false, false);
            
            // Atomically rename to final file
            if (!tempFile.renameTo(file)) {
                throw new IOException("Failed to rename temp file to " + file.getName());
            }
        } catch (Exception e) {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
            tempFile.delete();
            throw e;
        }
    }
    
    /**
     * Reads entire file into byte array
     */
    private byte[] readFileBytes(File file) throws IOException {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int bytesRead = fis.read(data);
            if (bytesRead != data.length) {
                throw new IOException("Failed to read entire file");
            }
            return data;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }
    
    /**
     * Converts byte array to hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Deletes all key files (use for testing or reset functionality)
     */
    public boolean deleteKeys() {
        boolean success = true;
        if (privateKeyFile.exists()) {
            success = privateKeyFile.delete() && success;
        }
        if (publicKeyFile.exists()) {
            success = publicKeyFile.delete() && success;
        }
        if (publicKeyAdbFile.exists()) {
            success = publicKeyAdbFile.delete() && success;
        }
        Log.d(TAG, "All key files deleted: " + success);
        return success;
    }
}
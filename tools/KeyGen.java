import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Генератор ключей активации SmartWallet. В приложение НЕ попадает.
 *
 * Запуск (нужен JDK 11+, например из Android Studio):
 *   java tools/KeyGen.java init                         — один раз: создать пару ключей
 *   java tools/KeyGen.java issue ABCD-EFGH-JKLM         — бессрочный ключ
 *   java tools/KeyGen.java issue ABCD-EFGH-JKLM 30      — ключ на 30 дней
 *
 * Закрытый ключ хранится в %USERPROFILE%\SmartWalletKeys\private_key.txt
 * (или в папке из переменной окружения SW_KEYS_DIR). Сделайте его резервную копию
 * и никому не передавайте: кто владеет им, тот может выпускать ключи.
 */
public class KeyGen {

    private static final String CODE_PATTERN = "[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}";
    private static final int LIFETIME_EXPIRY = 0xFFFF;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "init":
                init();
                break;
            case "issue":
                if (args.length < 2) {
                    usage();
                    return;
                }
                issue(args[1], args.length >= 3 ? args[2] : "lifetime");
                break;
            case "codes":
                codes(args.length >= 2 ? args[1] : "A:100:30");
                break;
            default:
                usage();
        }
    }

    // ---------- Тестовые коды SWT-… (их же узнаёт tools/license-server/worker.js) ----------

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Печатает коды партий и адрес админ-панели. Формат партий как у BATCHES: "A:100:30,B:50:14". */
    private static void codes(String spec) throws Exception {
        String pem = new String(Files.readAllBytes(privateKeyFile()), StandardCharsets.US_ASCII).replaceAll("\\s+", "");
        byte[] seed = java.security.MessageDigest.getInstance("SHA-256").digest(("SWT1|" + pem).getBytes(StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(seed, "HmacSHA256"));

        StringBuilder out = new StringBuilder();
        out.append("Админ-панель: https://<адрес-сервера>/a/")
           .append(toBase32(mac.doFinal("admin".getBytes(StandardCharsets.UTF_8)), 26)).append("\n\n");
        for (String part : spec.split(",")) {
            String[] p = part.trim().split(":");
            char letter = Character.toUpperCase(p[0].charAt(0));
            int count = Integer.parseInt(p[1]);
            out.append("Партия ").append(letter).append(": ").append(count).append(" кодов на ").append(p[2]).append(" дней\n");
            for (int n = 0; n < count; n++) {
                String body = "" + letter + ALPHABET.charAt(n >> 5) + ALPHABET.charAt(n & 31);
                String s = body + toBase32(mac.doFinal(("code|" + body).getBytes(StandardCharsets.UTF_8)), 9);
                out.append(String.format("%3d  SWT-%s-%s-%s%n", n + 1, s.substring(0, 4), s.substring(4, 8), s.substring(8)));
            }
            out.append("\n");
        }
        System.out.print(out);
    }

    private static String toBase32(byte[] bytes, int chars) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0, i = 0;
        while (out.length() < chars) {
            if (bits < 5) { buffer = (buffer << 8) | (bytes[i++] & 0xFF); bits += 8; }
            bits -= 5;
            out.append(ALPHABET.charAt((buffer >> bits) & 31));
        }
        return out.toString();
    }

    private static Path privateKeyFile() {
        Path privateFile = keysDir().resolve("private_key.txt");
        if (!Files.exists(privateFile)) {
            System.out.println("Не найден закрытый ключ " + privateFile + ". Сначала выполните: java tools/KeyGen.java init");
            System.exit(1);
        }
        return privateFile;
    }

    private static Path keysDir() {
        String custom = System.getenv("SW_KEYS_DIR");
        if (custom != null && !custom.isEmpty()) return Paths.get(custom);
        return Paths.get(System.getProperty("user.home"), "SmartWalletKeys");
    }

    private static void init() throws Exception {
        Path dir = keysDir();
        Path privateFile = dir.resolve("private_key.txt");
        Path publicFile = dir.resolve("public_key.txt");
        if (Files.exists(privateFile)) {
            System.out.println("Ключи уже существуют: " + privateFile);
            System.out.println("Новая пара сделает все выданные ключи недействительными в новых сборках.");
            System.out.println("Если это точно нужно — удалите файл вручную и запустите init снова.");
            return;
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();

        Files.createDirectories(dir);
        String privateB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicB64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        Files.write(privateFile, privateB64.getBytes(StandardCharsets.US_ASCII));
        Files.write(publicFile, publicB64.getBytes(StandardCharsets.US_ASCII));

        System.out.println("Готово. Закрытый ключ: " + privateFile);
        System.out.println();
        System.out.println("Открытый ключ — вставьте его в LicenseVerifier.PUBLIC_KEY_BASE64:");
        System.out.println(publicB64);
    }

    private static void issue(String rawCode, String term) throws Exception {
        String deviceCode = rawCode.trim().toUpperCase().replace(" ", "");
        if (!deviceCode.matches(CODE_PATTERN)) {
            System.out.println("Неверный код устройства: " + rawCode + " (ожидается вид ABCD-EFGH-JKLM)");
            System.exit(1);
        }

        char plan;
        int expiry;
        String description;
        if (term.equalsIgnoreCase("lifetime")) {
            plan = 'L';
            expiry = LIFETIME_EXPIRY;
            description = "навсегда";
        } else {
            int days = Integer.parseInt(term);
            if (days <= 0) throw new IllegalArgumentException("Количество дней должно быть больше нуля");
            LocalDate until = LocalDate.now(ZoneOffset.UTC).plusDays(days);
            plan = 'T';
            expiry = (int) until.toEpochDay();
            description = days + " дн. (до " + until + " включительно)";
        }

        PrivateKey privateKey = loadPrivateKey();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(("SW1|" + deviceCode + "|" + plan + "|" + expiry).getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        byte[] payload = new byte[4 + signature.length];
        payload[0] = 1;
        payload[1] = (byte) plan;
        payload[2] = (byte) (expiry >> 8);
        payload[3] = (byte) expiry;
        System.arraycopy(signature, 0, payload, 4, signature.length);

        String key = "SW-" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        System.out.println("Устройство: " + deviceCode);
        System.out.println("Срок:       " + description);
        System.out.println();
        System.out.println(key);
    }

    private static PrivateKey loadPrivateKey() throws Exception {
        Path privateFile = keysDir().resolve("private_key.txt");
        if (!Files.exists(privateFile)) {
            System.out.println("Не найден закрытый ключ " + privateFile + ". Сначала выполните: java tools/KeyGen.java init");
            System.exit(1);
        }
        byte[] der = Base64.getDecoder().decode(new String(Files.readAllBytes(privateFile), StandardCharsets.US_ASCII).trim());
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static void usage() {
        System.out.println("Использование:");
        System.out.println("  java tools/KeyGen.java init");
        System.out.println("  java tools/KeyGen.java issue <КОД-УСТРОЙСТВА> [дней|lifetime]");
        System.out.println("  java tools/KeyGen.java codes [A:100:30]   — тестовые коды SWT-… и адрес админ-панели");
    }
}

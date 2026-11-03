import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Готовит файлы релиза для удалённого обновления SmartWallet (в приложение не попадает).
 * Обычно запускается через tools\publish.bat, который сначала собирает release-APK.
 *
 *   java tools/Publish.java --version-code 3 --version-name 1.2 --changelog "Что нового"
 *        [--web-version 1] [--apk app/build/outputs/apk/release/app-release.apk]
 *        [--apk-tag v1.2] [--tag v1.2] [--repo MrKowar/SmartWallet] [--out dist]
 *
 * Результат в dist/: SmartWallet-<версия>.apk, web-bundle.zip, update.json.
 * Все три файла загружаются в GitHub Release с тегом --tag.
 * Только интерфейс (без нового APK): тот же --version-code, --web-version на 1 больше,
 * новый --tag (например v1.2-web2) и --apk-tag того релиза, где лежит APK.
 */
public class Publish {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        int versionCode = Integer.parseInt(required(a, "version-code"));
        String versionName = required(a, "version-name");
        int webVersion = Integer.parseInt(a.getOrDefault("web-version", "1"));
        String repo = a.getOrDefault("repo", "MrKowar/SmartWallet");
        String tag = a.getOrDefault("tag", "v" + versionName);
        String apkTag = a.getOrDefault("apk-tag", tag);
        String changelog = a.getOrDefault("changelog", "");
        Path apk = Paths.get(a.getOrDefault("apk", "app/build/outputs/apk/release/app-release.apk"));
        Path assets = Paths.get(a.getOrDefault("assets", "app/src/main/assets"));
        Path out = Paths.get(a.getOrDefault("out", "dist"));

        if (!Files.isRegularFile(apk)) fail("Не найден APK: " + apk + " (сначала соберите release)");
        Files.createDirectories(out);

        String apkName = "SmartWallet-" + versionName + ".apk";
        Files.copy(apk, out.resolve(apkName), StandardCopyOption.REPLACE_EXISTING);
        String apkSha = sha256Hex(Files.readAllBytes(apk));

        byte[] zip = zipDirectory(assets);
        Files.write(out.resolve("web-bundle.zip"), zip);
        String message = "SWWEB1|" + versionCode + "|" + webVersion + "|" + sha256Hex(zip);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(loadPrivateKey());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        String base = "https://github.com/" + repo + "/releases/download/";
        String json = "{\n"
            + "  \"versionCode\": " + versionCode + ",\n"
            + "  \"versionName\": " + quote(versionName) + ",\n"
            + "  \"changelog\": " + quote(changelog) + ",\n"
            + "  \"apkUrl\": " + quote(base + apkTag + "/" + apkName) + ",\n"
            + "  \"apkSha256\": " + quote(apkSha) + ",\n"
            + "  \"web\": {\n"
            + "    \"versionCode\": " + versionCode + ",\n"
            + "    \"webVersion\": " + webVersion + ",\n"
            + "    \"url\": " + quote(base + tag + "/web-bundle.zip") + ",\n"
            + "    \"signature\": " + quote(signature) + "\n"
            + "  }\n"
            + "}\n";
        Files.write(out.resolve("update.json"), json.getBytes(StandardCharsets.UTF_8));

        System.out.println("Готово: " + out.toAbsolutePath());
        System.out.println("  " + apkName + "  sha256 " + apkSha);
        System.out.println("  web-bundle.zip  (интерфейс v" + webVersion + " для versionCode " + versionCode + ")");
        System.out.println("  update.json");
        System.out.println();
        System.out.println("Загрузите файлы в GitHub Release " + tag + ":");
        System.out.println("  gh release create " + tag + " " + out + "/" + apkName + " " + out + "/web-bundle.zip " + out + "/update.json --title \"SmartWallet " + versionName + "\" --notes \"" + changelog.replace("\"", "'") + "\"");
        System.out.println("или вручную: https://github.com/" + repo + "/releases/new");
    }

    private static byte[] zipDirectory(Path dir) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes); Stream<Path> files = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                String name = dir.relativize(file).toString().replace('\\', '/');
                if (name.startsWith("_")) continue; // временные файлы предпросмотра
                zos.putNextEntry(new ZipEntry(name));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static PrivateKey loadPrivateKey() throws Exception {
        String custom = System.getenv("SW_KEYS_DIR");
        Path dir = (custom != null && !custom.isEmpty()) ? Paths.get(custom) : Paths.get(System.getProperty("user.home"), "SmartWalletKeys");
        Path file = dir.resolve("private_key.txt");
        if (!Files.exists(file)) fail("Не найден закрытый ключ " + file);
        byte[] der = Base64.getDecoder().decode(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim());
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            if (!args[i].startsWith("--")) fail("Неожиданный аргумент: " + args[i]);
            map.put(args[i].substring(2), args[i + 1]);
        }
        return map;
    }

    private static String required(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isEmpty()) fail("Не указан --" + key);
        return v;
    }

    private static void fail(String message) {
        System.out.println(message);
        System.exit(1);
    }
}

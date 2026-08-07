import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.RSA;
import java.nio.charset.StandardCharsets;

/** 用仓库 .env 的公钥生成传输层密文，等价前端 JSEncrypt.encrypt（仅本机冒烟用） */
public class RsaEnc {
    public static void main(String[] args) {
        String pub = System.getenv("DAKANG_RSA_PUBLIC_KEY");
        if (pub == null || pub.isBlank()) { System.err.println("missing DAKANG_RSA_PUBLIC_KEY"); System.exit(1); }
        RSA rsa = new RSA(null, pub);
        System.out.println(rsa.encryptBase64(args[0].getBytes(StandardCharsets.UTF_8), KeyType.PublicKey));
    }
}

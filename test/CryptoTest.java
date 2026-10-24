import app.tilescreen.core.Crypto;
public class CryptoTest {
    static byte[] hex(String s) { s = s.replaceAll("\\s",""); byte[] b = new byte[s.length()/2]; for (int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16); return b; }
    static String hx(byte[] b) { StringBuilder s=new StringBuilder(); for (byte x:b) s.append(String.format("%02x",x)); return s.toString(); }
    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("x")) { System.out.println(hx(Crypto.x25519(hex(a[1]), hex(a[2])))); return; }
        if (a.length > 0 && a[0].equals("seal")) { System.out.println(hx(Crypto.seal(hex(a[1]), hex(a[2]), hex(a[3]), hex(a[4])))); return; }
        // RFC 7748 5.2
        String r = hx(Crypto.x25519(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")));
        System.out.println("x25519 vector " + r.equals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"));
        // RFC 7748 6.1 Alice public
        System.out.println("x25519 base   " + hx(Crypto.x25519Public(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))).equals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"));
        // RFC 8439 2.8.2
        byte[] key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = hex("070000004041424344454647");
        byte[] aad = hex("50515253c0c1c2c3c4c5c6c7");
        byte[] pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".getBytes("US-ASCII");
        byte[] sealed = Crypto.seal(key, nonce, aad, pt);
        System.out.println("aead tag      " + hx(sealed).endsWith("1ae10b594f09e26a7e902ecbd0600691"));
        System.out.println("aead open     " + new String(Crypto.open(key, nonce, aad, sealed), "US-ASCII").equals(new String(pt, "US-ASCII")));
        sealed[3] ^= 1;
        System.out.println("aead tamper   " + (Crypto.open(key, nonce, aad, sealed) == null));
        System.out.println("base64        " + Crypto.base64(hex("00ff10")).equals("AP8Q") + " " + hx(Crypto.unbase64("AP8Q")).equals("00ff10"));
    }
}

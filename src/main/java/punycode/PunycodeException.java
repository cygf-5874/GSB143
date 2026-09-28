package punycode;

/** Punycode 编解码中遇到非法输入 / 溢出时抛出。**已给全，勿改**。 */
public class PunycodeException extends RuntimeException {

    public PunycodeException(String message) {
        super(message);
    }
}
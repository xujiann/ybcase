package cn.ybcase.bureau.spi;

/** 电子签章 SPI：实施期替换为政务 CA 提供方实现（Bean 覆盖即可，业务代码零改动） */
public interface SignatureProvider {

    record SignResult(String contentHash, String signature, String provider) {}

    SignResult sign(String content, String signer);

    /** 验签：内容未被篡改且签章有效 */
    boolean verify(String content, String contentHash, String signature);
}

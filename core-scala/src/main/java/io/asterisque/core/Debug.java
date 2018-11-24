package io.asterisque.core;

import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * デバッグ用のユーティリティ機能です。
 *
 * @author Takami Torao
 */
public final class Debug {

  /**
   * コンストラクタはクラス内に隠蔽されています。
   */
  private Debug() {
  }

  /**
   * 指定されたインスタンスをデバッグ用に人間が読みやすい形式に変換します。
   *
   * @param value 文字列化するオブジェクト
   * @return デバッグ用の文字列
   */
  @Nonnull
  public static String toString(@Nullable Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Character) {
      return "\'" + escape((Character) value) + "\'";
    }
    if (value instanceof String) {
      String str = (String) value;
      return "\"" + str.chars().mapToObj(ch -> Debug.escape((char) ch)).collect(Collectors.joining()) + "\"";
    }
    if (value instanceof InetSocketAddress) {
      InetSocketAddress i = (InetSocketAddress) value;
      return i.getHostName() + ":" + i.getPort();
    }
    if (value instanceof InetAddress) {
      InetAddress i = (InetAddress) value;
      return i.getHostName() + "/" + i.getHostAddress();
    }
    if (value instanceof Optional<?>) {
      Optional<?> o = (Optional<?>) value;
      return o.map(x -> "Some(" + toString(x) + ")").orElse("None");
    }
    if (value instanceof Map<?, ?>) {
      return "{" + String.join(",",
          ((Map<?, ?>) value).entrySet().stream()
              .map(e -> new String[]{toString(e.getKey()), toString(e.getValue())})
              .sorted(Comparator.comparing(a -> a[0]))
              .map(a -> a[0] + ":" + a[1])
              .toArray(String[]::new)
      ) + "}";
    }
    if (value instanceof Collection<?>) {
      return "[" + String.join(",",
          (((Collection<?>) value).stream()
              .map(Debug::toString)
              .toArray(String[]::new)
          )
      ) + "]";
    }
    if (value instanceof byte[]) {
      byte[] b = (byte[]) value;
      StringBuilder buffer = new StringBuilder(b.length * 2);
      for (byte b1 : b) {
        buffer.append(String.format("%02X", b1 & 0xFF));
      }
      return buffer.toString();
    }
    if (value instanceof char[]) {
      return toString(new String((char[]) value));
    }
    if (value instanceof Object[]) {
      return toString(Arrays.asList((Object[]) value));
    }
    if(value instanceof ByteBuffer){
      ByteBuffer b = (ByteBuffer)value;
      byte[] bin = new byte[b.remaining()];
      int pos = b.position();
      b.get(bin, 0, bin.length);
      b.position(pos);
      return toString(bin);
    }
    return value.toString();
  }

  private static final Map<Character, String> ESCAPE = new HashMap<>();

  static {
    ESCAPE.put('\0', "\\0");
    ESCAPE.put('\b', "\\b");
    ESCAPE.put('\f', "\\f");
    ESCAPE.put('\n', "\\n");
    ESCAPE.put('\r', "\\r");
    ESCAPE.put('\t', "\\t");
    ESCAPE.put('\\', "\\\\");
    ESCAPE.put('\'', "\\\'");
    ESCAPE.put('\"', "\\\"");
  }

  /**
   * 指定された文字を Java の文字列リテラルとして使用できるようにエスケープします。
   *
   * @param ch エスケープする文字
   * @return エスケープされた文字
   */
  public static String escape(char ch) {
    String value = ESCAPE.get(ch);
    if (value == null) {
      if (Character.isISOControl(ch) || !Character.isDefined(ch)) {
        return "\\u" + String.format("%04X", (int) ch);
      }
      return String.valueOf(ch);
    }
    return value;
  }

  /**
   * 指定されたメソッドを人が判別できるシンプルな文字列に変換します。
   *
   * @param m 文字列化するメソッド
   * @return メソッドを識別する文字列
   */
  public static String getSimpleName(Method m) {
    return m.getDeclaringClass().getSimpleName() +
        "." + m.getName() +
        "(" + Stream.of(m.getParameterTypes()).map(Class::getSimpleName).reduce((a, b) -> a + "," + b).orElse("") +
        "):" + m.getReturnType().getSimpleName();
  }

  public static void dumpCertificate(Logger logger, String prefix, Certificate cs) {
    if (logger.isTraceEnabled()) {
      if (cs instanceof X509Certificate) {
        DateFormat df = DateFormat.getDateTimeInstance();
        X509Certificate c = (X509Certificate) cs;
        logger.trace(String.format("%s: Serial Number: %s", prefix, c.getSerialNumber()));
        logger.trace(String.format("%s: Signature Algorithm: %s", prefix, c.getSigAlgName()));
        logger.trace(String.format("%s: Signature Algorithm OID: %s", prefix, c.getSigAlgOID()));
        logger.trace(String.format("%s: Issuer Principal Name: %s", prefix, c.getIssuerX500Principal().getName()));
        logger.trace(String.format("%s: Subject Principal Name: %s", prefix, c.getSubjectX500Principal().getName()));
        logger.trace(String.format("%s: Expires: %s - %s", prefix, df.format(c.getNotBefore()), df.format(c.getNotAfter())));
      } else {
        logger.trace(String.format("%s: Type: %s", prefix, cs.getType()));
        logger.trace(String.format("%s: Public Key Algorithm: %s", prefix, cs.getPublicKey().getAlgorithm()));
        logger.trace(String.format("%s: Public Key Format: %s", prefix, cs.getPublicKey().getFormat()));
      }
    }
  }

  public static void dumpSSLSession(Logger logger, String prefix, SSLSession session) throws SSLPeerUnverifiedException {
    if (logger.isTraceEnabled()) {
      logger.trace(String.format("%s: CipherSuite   : %s", prefix, session.getCipherSuite()));
      logger.trace(String.format("%s: LocalPrincipal: %s", prefix, session.getLocalPrincipal().getName()));
      logger.trace(String.format("%s: PeerHost      : %s", prefix, session.getPeerHost()));
      logger.trace(String.format("%s: PeerPort      : %s", prefix, session.getPeerPort()));
      logger.trace(String.format("%s: PeerPrincipal : %s", prefix, session.getPeerPrincipal().getName()));
      for (Certificate cs : session.getPeerCertificates()) {
        dumpCertificate(logger, prefix, cs);
      }
    }
  }

}

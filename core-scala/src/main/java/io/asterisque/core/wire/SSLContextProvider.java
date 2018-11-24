package io.asterisque.core.wire;

import io.asterisque.Asterisque;

import javax.annotation.Nonnull;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Optional;

/**
 * ローカルファイルとして保存されている KeyStore をロードし SSLContext を作成するクラスです。
 */
public class SSLContextProvider {
  private static final String DEFAULT_PROTOCOL = "TLS";
  private static final String DEFAULT_ALGORITHM = "SunX509";
  private static final String DEFAULT_KEYSTORE_TYPE = "JKS";

  /**
   * キーストアの URI
   */
  private final String location;

  /**
   * キーストア
   */
  private final KeyStore keyStore;


  /**
   * パスワード無指定 (長さ 0 の文字列) の KeyStore ファイルをロードします。ファイルは Java 標準の keytool などを使用した
   * JKS 形式で保存されている必要があります。
   *
   * @param file KeyStore ファイル
   */
  public SSLContextProvider(@Nonnull File file) {
    this(file, Asterisque.Empty.Chars, DEFAULT_KEYSTORE_TYPE);
  }

  /**
   * Java 標準の keytool を使用して保存されている JKS 形式の KeyStore ファイルをロードします。
   *
   * @param file     KeyStore ファイル
   * @param password KeyStore ファイルのパスワード
   */
  public SSLContextProvider(@Nonnull File file, @Nonnull char[] password) {
    this(file, password, DEFAULT_KEYSTORE_TYPE);
  }

  /**
   * 指定された KeyStore ファイルをロードしてインスタンスを構築します。
   *
   * @param file         KeyStore ファイル
   * @param password     KeyStore ファイルのパスワード
   * @param keyStoreType KeyStore ファイルのタイプ
   */
  public SSLContextProvider(@Nonnull File file, @Nonnull char[] password, @Nonnull String keyStoreType) {
    this.location = file.getAbsolutePath();
    try (InputStream in = new FileInputStream(file)) {
      this.keyStore = newKeyStore(in, this.location, password, keyStoreType);
    } catch (IOException ex) {
      throw new Exception("specified key-store location cannot load: " + location, ex);
    }
  }

  /**
   * 指定されたストリームから KeyStore をロードします。
   *
   * @param in           KeyStore の入力ストリーム
   * @param location     KeyStore のロケーションを表す場所 (問題分析用の人が読める形式)
   * @param password     KeyStore ファイルのパスワード
   * @param keyStoreType KeyStore ファイルのタイプ
   * @return ロードした KeyStore
   */
  private static KeyStore newKeyStore(@Nonnull InputStream in, @Nonnull String location,
                                      @Nonnull char[] password, @Nonnull String keyStoreType) {
    try {
      KeyStore keyStore = KeyStore.getInstance(keyStoreType);
      keyStore.load(in, password);
      return keyStore;
    } catch (IOException ex) {
      throw new Exception("specified key-store location cannot load: " + location, ex);
    } catch (CertificateException ex) {
      throw new Exception("the certificates cannot load: " + location, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new Exception("invalid key-store algorithm: [" + keyStoreType + "] " + location, ex);
    } catch (KeyStoreException ex) {
      throw new Exception("invalid key-store location: " + location, ex);
    }
  }

  /**
   * このキーストアに保存されている X.509 形式のエントリから TLS プロトコルのコンテキストをロードします。
   *
   * @return SSL コンテキスト
   */
  public SSLContext newSSLContext() {
    return newSSLContext(DEFAULT_PROTOCOL, DEFAULT_ALGORITHM, Asterisque.Empty.Chars);
  }

  /**
   * このキーストアに保存されている指定された形式のエントリから protocol プロトコルのコンテキストをロードします。
   *
   * @param protocol  {@code TLS} などのプロトコル
   * @param algorithm {@code SunX509} などの形式
   * @param password  キーストアエントリのパスワード
   * @return SSL コンテキスト
   */
  public SSLContext newSSLContext(@Nonnull String protocol, @Nonnull String algorithm, @Nonnull char[] password) {
    algorithm = Optional.ofNullable(Security.getProperty(algorithm)).orElse(algorithm);
    try {
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
      kmf.init(keyStore, password);
      KeyManager[] keyManagers = kmf.getKeyManagers();
      TrustManager[] trustManagers = null;
      SSLContext sslContext = SSLContext.getInstance(protocol);
      sslContext.init(keyManagers, trustManagers, null);
      return sslContext;
    } catch (UnrecoverableKeyException ex) {
      throw new Exception("key cannot resolved in location: " + location, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new Exception("algorithm unavailable: " + algorithm, ex);
    } catch (KeyStoreException ex) {
      throw new Exception("invalid key store: " + location, ex);
    } catch (KeyManagementException ex) {
      throw new Exception(ex);
    }
  }

  /**
   * {@link SSLContextProvider} で発生する例外です。
   */
  public static class Exception extends RuntimeException {
    private Exception(@Nonnull String message, @Nonnull Throwable ex) {
      super(message, ex);
    }

    public Exception(@Nonnull Throwable ex) {
      super(ex);
    }
  }
}

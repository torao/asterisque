package io.asterisque.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;

public final class IO {
  private static final Logger logger = LoggerFactory.getLogger(IO.class);

  /**
   * コンストラクタはクラス内に隠蔽されています。
   */
  private IO() {
  }

  public static <P extends AutoCloseable, R> R using(@Nullable P resource, @Nonnull Function<P, R> f) {
    try {
      return f.apply(resource);
    } finally {
      if (resource != null) {
        try {
          resource.close();
        } catch (Exception ex) {
          logger.warn("fail to close resource {}", resource, ex);
        }
      }
    }
  }
}

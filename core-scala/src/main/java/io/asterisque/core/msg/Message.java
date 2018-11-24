package io.asterisque.core.msg;

import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * asterisque の通信で使用するメッセージです。
 *
 * @author Takami Torao
 */
public abstract class Message implements Serializable {

  /**
   * このメッセージの宛先を示すパイプ ID です。
   */
  public final short pipeId;

  /**
   * インスタンスは同一パッケージ内のサブクラスからのみ構築することが出来ます。
   *
   * @param pipeId メッセージの宛先となるパイプ ID
   */
  Message(short pipeId) {
    this.pipeId = pipeId;
  }

  @Override
  public int hashCode() {
    return pipeId & 0xFFFF;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof Message) {
      return ((Message) obj).pipeId == this.pipeId;
    }
    return false;
  }
}

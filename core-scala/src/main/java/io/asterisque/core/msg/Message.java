package io.asterisque.msg;

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
    if (pipeId == 0 && !(this instanceof Control)) {
      throw new IllegalArgumentException("pipe-id should be zero if only Control message: " + pipeId);
    }
    this.pipeId = pipeId;
  }
}

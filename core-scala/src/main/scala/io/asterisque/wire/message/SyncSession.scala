package io.asterisque.wire.message

import java.util.Objects

import io.asterisque.ProtocolViolationException
import io.asterisque.auth.Certificate
import io.asterisque.core.codec.MessageFieldCodec
import io.asterisque.core.Spec
import io.asterisque.utils.{Debug, Version}
import io.asterisque.wire.Spec
import javax.annotation.Nonnull


/**
  * 通信の開始時にピア間の設定を同期するための制御メッセージに付随するバイナリフィールドを参照するためのヘルパークラスです。
  * [[Control#SyncSession]] の制御コードを持つ Control メッセージのデータに使用できます。
  *
  * @param version   プロトコルのバージョンを表す 2 バイト整数値。上位バイトから [major][minor] の順を持つ。[[Spec]] 参照。
  * @param cert      ノード証明書。通信が TLS を使用している場合はそちらの証明書と照合する必要がある。
  * @param sessionId セッション ID 同期。接続後のクライアント Sync に対するサーバ応答でのみ有効な値を持つ。それ以外の場合は[[io.asterisque.Asterisque#Zero]] を送らなければならず、受け取った側は無視しなければならない。
  * @param serviceId このセッションで処理要求の対象とするサービス名。
  * @param utcTime   UTC ミリ秒で表現した現在時刻。システム時刻確認の目的で使用される。
  * @param config    セッションのコンフィギュレーション
  * @author Takami Torao
  */
final case class SyncSession(@Nonnull version:Version, @Nonnull cert:Certificate, @Nonnull sessionId:Long, @Nonnull serviceId:String, utcTime:Long, config:Map[String, String]) {
  // TODO ping, sessionTimeout の入力値チェックとテストケース作成
  Objects.requireNonNull(cert)
  Objects.requireNonNull(sessionId)
  Objects.requireNonNull(serviceId)
  if(serviceId.getBytes(Spec.Std.charset).length > Spec.Std.maxServiceId) {
    throw new IllegalArgumentException(s"service id too long: $serviceId must be less than or equal ${Spec.Std.maxServiceId}")
  }

  /**
    * このインスタンスの内容から SyncSession バイナリのデータを持った Control メッセージを作成します。
    *
    * @return Control メッセージ
    */
  @Nonnull
  def toControl:Control = {
    new Control(Control.SyncSession, Codec.SYNC_SESSION.encode(this))
  }
}

object SyncSession {

  /**
    * 現在のバージョンを使用して構築します。
    */
  def apply(@Nonnull cert:Certificate, @Nonnull sessionId:Long, @Nonnull serviceId:String, utcTime:Long, attrs:Map[String, String]) {
    SyncSession(Spec.Version, cert, sessionId, serviceId, utcTime, attrs)
  }

  /**
    * 指定された Control メッセージからセッション同期のインスタンスを構築します。
    *
    * @param control Control メッセージ
    * @throws ProtocolViolationException asterisque のストリームではない場合
    */
  @Nonnull
  @throws[ProtocolViolationException]
  def parse(@Nonnull control:Control):SyncSession = {
    if(control.code != Control.SyncSession) {
      throw new ProtocolViolationException(f"invalid asterisque protocol: 0x${MessageFieldCodec.Msg.Control & 0xFF}%02X${control.code & 0xFF}%02X")
    }
    try {
      Codec.SYNC_SESSION.decode(control.data)
    } catch {
      case ex:Exception =>
        throw new ProtocolViolationException(s"invalid sync-session: ${Debug.toString(control.data)}", ex)
    }
  }

}

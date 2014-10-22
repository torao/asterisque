/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import io.netty.handler.ssl.SslHandler
import io.netty.channel.{Channel, ChannelHandlerContext, SimpleChannelInboundHandler}
import com.kazzla.asterisk._
import org.slf4j.LoggerFactory
import scala.concurrent.Promise
import javax.net.ssl.SSLSession
import io.netty.util.concurrent.{Future => NFuture, GenericFutureListener}
import java.io.IOException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.security.cert.X509Certificate
import java.text.DateFormat

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// WireConnect
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class WireConnect(sslHandler:Option[SslHandler], isServer:Boolean, onWireCreate:(NettyWire)=>Unit) extends SimpleChannelInboundHandler[Message] {
	import WireConnect._

	private[this] def sym = if(isServer) "S" else "C"

	/**
	 * この接続の Wire。
	 */
	@volatile
	private[this] var wire:Option[NettyWire] = None

	/**
	 * ログ上で Wire の動作を識別するための ID 番号。
	 */
	private[this] val id = WireConnect.seq.getAndIncrement & Int.MaxValue

	// ==============================================================================================
	// チャネルの接続
	// ==============================================================================================
	/**
	 * 接続が完了したときに呼び出されます。
	 * SSL ハンドシェイクの完了処理を実装します。
	 * @param ctx コンテキスト
	 */
	override def channelActive(ctx:ChannelHandlerContext):Unit = {
		logger.trace(s"$sym[$id]: channelActive(${ctx.name()})")
		assert(wire.isEmpty)
		val promise = Promise[Option[SSLSession]]()
		sslHandler match {
			case Some(h) =>
				h.handshakeFuture().addListener(new GenericFutureListener[NFuture[Channel]] {
					def operationComplete(future:NFuture[Channel]):Unit = {
						val session = h.engine().getSession
						if(session.isValid){
							promise.success(Some(session))
							logger.debug(s"$sym[$id]: tls handshake success")
							if(logger.isTraceEnabled){
								session.getPeerCertificates.foreach{ c => logger.dump(c) }
							}
						} else {
							promise.failure(new IOException("tls handshake failure: invalid session"))
							logger.debug(s"$sym[$id]: tls handshake failure: invalid session")
						}
						if(logger.isTraceEnabled){
							val df = DateFormat.getDateTimeInstance
							session.getPeerCertificates.foreach{
								case cert:X509Certificate =>
									logger.trace(s"$sym[$id]:   Serial Number: ${cert.getSerialNumber}")
									logger.trace(s"$sym[$id]:   Signature Algorithm: ${cert.getSigAlgName}")
									logger.trace(s"$sym[$id]:   Signature Algorithm OID: ${cert.getSigAlgOID}")
									logger.trace(s"$sym[$id]:   Issuer Principal Name: ${cert.getIssuerX500Principal.getName}")
									logger.trace(s"$sym[$id]:   Subject Principal Name: ${cert.getSubjectX500Principal.getName}")
									logger.trace(s"$sym[$id]:   Expires: ${df.format(cert.getNotBefore)} - ${df.format(cert.getNotAfter)}")
								case cert =>
									logger.trace(s"$sym[$id]:   Type: ${cert.getType}")
									logger.trace(s"$sym[$id]:   Public Key Algorithm: ${cert.getPublicKey.getAlgorithm}")
									logger.trace(s"$sym[$id]:   Public Key Format: ${cert.getPublicKey.getFormat}")
							}
						}
					}
				})
			case None =>
				promise.success(None)
		}
		wire = Some(NettyWire(ctx.channel().remoteAddress(), isServer, promise.future, ctx))
		super.channelActive(ctx)

		// 接続完了を通知
		onWireCreate(wire.get)
	}

	// ==============================================================================================
	// チャネルの切断
	// ==============================================================================================
	/**
	 * @param ctx コンテキスト
	 */
	override def channelInactive(ctx:ChannelHandlerContext):Unit = {
		logger.trace(s"$sym[$id]: channelInactive(${ctx.name()})")
		closeWire()
		super.channelInactive(ctx)
	}

	private val authed = new Once()

	// ==============================================================================================
	// メッセージの受信
	// ==============================================================================================
	/**
	 * @param ctx コンテキスト
	 * @param msg メッセージ
	 */
	override def channelRead0(ctx:ChannelHandlerContext, msg:Message):Unit = {
		logger.trace(s"$sym[$id]: channelRead0(${ctx.name()},$msg)")
		authed {
			// パイプラインの SSL ハンドラから接続相手の SSL 情報を出力
			Option(ctx.channel().pipeline().get(classOf[SslHandler])).foreach { s =>
				val session = s.engine().getSession
				if(logger.isTraceEnabled){
					logger.trace(s"$sym: CipherSuite   : ${session.getCipherSuite}")
					logger.trace(s"$sym: LocalPrincipal: ${session.getLocalPrincipal.getName}")
					logger.trace(s"$sym: PeerHost      : ${session.getPeerHost}")
					logger.trace(s"$sym: PeerPort      : ${session.getPeerPort}")
					logger.trace(s"$sym: PeerPrincipal : ${session.getPeerPrincipal.getName}")
				}
			}
		}

		// メッセージを通知
		wire.foreach{ _._receive(msg) }

		// super.channelRead0(ctx, msg) スーパークラスは未実装
	}

	// ==============================================================================================
	// 例外の発生
	// ==============================================================================================
	/**
	 * @param ctx コンテキスト
	 * @param cause 発生した例外
	 */
	override def exceptionCaught(ctx:ChannelHandlerContext, cause:Throwable):Unit = {
		logger.debug(s"$sym[$id]: exception caught", cause)
		closeWire()
	}

	// ==============================================================================================
	// ワイヤーのクローズ
	// ==============================================================================================
	/**
	 */
	private[this] def closeWire():Unit = {
		logger.trace(s"$sym[$id]: closeWire()")
		wire.foreach{ _.close() }
		wire = None
	}

}

private[netty] object WireConnect {
	private[WireConnect] val logger = LoggerFactory.getLogger(classOf[WireConnect])

	/**
	 * WireConnect の ID 生成のためのシーケンス番号。
	 */
	private[WireConnect] val seq = new AtomicInteger(0)
}


/**
 * 処理を一度だけ実行する。
 */
private[netty] class Once {
	private val first = new AtomicBoolean(true)
	def apply(f: =>Unit) = if(first.compareAndSet(true, false)){
		f
	}
}

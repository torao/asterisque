## Developer Guide

**STATUS:** 書きかけ

### イントロダクション

asterisque は非同期 I/O の煩雑さをうまくラップしインターフェースの共有による手軽で型安全な非同期 RPC、メッセージパッシング、データストリーミングを共存実装できるように設計を行っています。

asterisque を使用した現実的なアプリケーション実装においては `Future` や `ExecutionContext` などの理解が必要となります。実装者は Scala での非同期処理や並行処理に関する基本な知識を持っている事を前提としています。

### 構成の概要

最初に asterisque の基本的な構成と各コンポーネントの役割について説明します。

#### ネットワークの構成

asterisque は多数の **ノード** (Node) を **ワイヤー** (Wire) で接続することで P2P 型の仮想ネットワークを形成します。ワイヤーは 2 つのノードをデータ欠損のない順序の保証された方法で永続的に接続し、非同期かつ双方向に **メッセージ** (Message) を伝達するネットワーク実装です。具体的には TLS over TCP や AMQP、プロセス間通信、あるいは同一プロセス内でのスレッド間通信などでの実装を想定しています。

TCP/IP を使用するワイヤー上の通信は TLS によって暗号化されます。ネットワークを構成するノードの多くはヒューマンインタラクティブな状態ではなく、ノードはクライアント認証を使用して自分が何者であるかを証明します。

![Network](images/biRPC-NodeNetwork.png)

#### 機能の公開と利用

アプリケーションは任意の **サービス** (Service) を実装してノード上で公開することができます。サービスとはノードに接続しているピアが呼び出す事の出来る **ファンクション** (Function) の集合です。ファンクションは asterisque におけるリモート呼び出し可能な処理のエントリポイントを表します。ファンクションは同一サービス内で 2 バイト整数で識別されます。このため同時に公開できるのは最大65,536個までです。

例として何らかの CRUD 操作をおこなうサービスのインターフェースを定義します (実装の詳細は [サービスの実装と呼び出し](#ServiceImplementationAndRemoteCall) を参照)。

```scala
trait CRUDService {
  @Export(10) def create(key:String, value:String):Future[Unit]
  @Export(20) def read(key:String):Future[String]
  @Export(30) def update(key:String, value:String):Future[Unit]
  @Export(40) def delete(key:String):Future[Unit]
}
```

セキュリティ的な理由から接続中のピアがどのようなサービスを公開しているかを知る標準的な方法を定義していません。これにはアプリケーションがサービス不明のピアに接続することはないという暗黙的な前提をおいています。アプリケーションはどのファンクションをどの番号でピアに公開するか、あるいはしないかを若干の低レベルな操作だけで実行時に決定することが出来ます。つまりピアによって更新系処理を公開しない選択をしたり、ピアごとにファンクション番号を変更することでサービススキャンのような攻撃を難しくする実装も可能です。もし本当にシステム設計がピアのファンクション一覧を必要とするなら、例えばシステム上のすべてのサービスにおいてファンクション 0 はサービス識別子を参照するファンクションといった実装を行うことが出来ます。

ファンクションは一般的な言語の関数/メソッドと同様に任意数のパラメータを指定して開始し単一の結果 (正常な処理結果または例外のどちらか) を戻すことが出来ます。ファンクションは非同期かつ並列処理での実装を前提としており、呼び出しは直ちに `Future` を返して終了する必要があります。処理に時間がかかる場合は別スレッドで処理を行い、処理の終了とその結果を`Future` 経由で通知することで呼び出しのノードが結果を受け取ります。

#### 接続と呼び出し状態の管理

ノード上で新しいワイヤー (接続) が発生すると **セッション** (Session) が生成されます。セッションはワイヤーと 1:1 の関係でスコープもほぼ等価ですが、Socket などの低レベルな通信状態の管理をファンクションの呼び出し状態の管理から分離することで、通信実装の差し替えが出来るように構成しています。

セッションはピアに公開するサービスを 1 つ持ちます。アプリケーションはピアとのセッションの途中で他のセッションに影響を与えずピアに対するサービスを変更することが出来ます。

ピアのファンクションを呼び出した時、あるいはピアからファンクションが呼び出された時、双方のセッション上に **パイプ** (Pipe) と呼ばれる呼び出しの状態が発生します。パイプはファンクション実行中に送受信するメッセージの宛先を表し、呼び出し開始から処理の終了までのスコープを持ちます。

アプリケーションはこのパイプを使用して *メッセージング* を行うことが出来ます。メッセージングは双方のパイプに対する **ブロック** の送受信です。アプリケーションはこのメッセージング機構を利用して双方向の非同期メッセージパッシングやストリーミングを行うことが出来ます。

![dir](images/biRPC-Bidirection.png)

一般的な RPC で提供されるような短時間で終了するファンクション呼び出しで発生するパイプは短命です。しかしメッセージングを伴うパイプはそれが終了するまで維持されるため長期的に存在します。セッションが終了するとそのセッション上のパイプもすべて消滅します。

パイプは同一セッション内で 2 バイト整数によって識別されます。新しいパイプを生成する (相手のファンクションを呼び出す) 時に相手の合意なしにユニークなパイプ ID を決定できるよう、ID の最上位ビット 1 または 0 をどちらが使用するかを接続時に決定します。このためある時点で片方のノードから同時に実行できる呼び出しは最大で 32,768 個、双方で 65,535 個となります。

#### メッセージの種類

asterisque が目指したゴールはメッセージングが可能な双方向の非同期 RPC です。単純な RPC 型プロトコルであれば HTTP のようなリクエスト-レスポンス型のメッセージで十分ですが、メッセージングとしてのモデルを考えた場合:

1. メッセージングの開始。ピアの双方でメッセージングのためのパイプを開く。
1. そのパイプを通して双方のプロセスがメッセージを送受信する。
1. メッセージングが終了すれば結果付きでパイプを閉じる。

とすることで概念的にもすっきりします。メッセージングを行わずオープン-クローズのみで完結する通信は先述の "単純な RPC 型プロトコル" と同じリクエスト-レスポンス型と同じスキームですので、上位互換の設計として RPC とメッセージングをうまく統合することが出来ます。

このような経緯で asterisque では以下の 3 種類のメッセージを導入しています。

1. **Open** … パイプID，ファンクション番号、パラメータを指定して該当ファンクションに対するパイプをオープンする。
1. **Close** … パイプIDを指定して該当するパイプを閉じる。正常な処理結果かエラーメッセージのどちらかを伴う。
1. **Block** … パイプIDを指定して任意のバイナリデータを転送する。

Open は常に呼び出し側 caller から送信されますが、Close 及び Block は caller, callee のどちらからでも送信が可能です。

![rpc call sequence](images/biRPC-Sequence1.png)

#### 通信レイヤーの実装

ワイヤーは様々な実装が可能です。TCP/IP ソケットのような典型的な listen, connect 操作でワイヤーを生成する場合はワイヤーのファクトリとして **ブリッジ** ([Bridge](https://github.com/torao/asterisk/blob/master/src/main/scala/com/kazzla/asterisk/Bridge.scala)) として実装することができます。標準では Netty を使用したブリッジが提供されています (Java 標準の SSLSocket は非同期 I/O に対応しておらず SocketChannel を使用して独自の SSL 実装を追加する必要があったため Netty を使用しました)。

```scala
trait Bridge {
  def connect(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire]
  def listen(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Future[Server]
}
class Server(val address:SocketAddress) extends Closeable { def close():Unit = None }
```

ブリッジは `connect()` で指定したアドレスのノードに接続し `Wire` の Future を返します。同様に `listen()` は指定されたアドレスにバインドしピアからの接続を受け付けると `onAccept` を呼び出します。それぞれが生成するワイヤーでは指定された **コーデック** (Codec) を使用してメッセージのシリアライズが行われます。ブリッジのインスタンスは Singleton で使用されるためスレッドセーフに実装する必要があります。

このようなネットワークを前提とした設計のブリッジでなく、アプリケーションが定義するメッセージの伝達経路を独自のワイヤーとして実装することができます。その一つの実装としてネットワークを使用せず二つのノードインスタンス間で直接メッセージを伝達するワイヤーのペアを標準で使用することが出来ます。

```scala
val (wire1, wire2) = Wire.newPipe()
val session1 = node1.bind(wire1)
val session2 = node2.bind(wire2)
```

上記の例はパイプ化されたワイヤーペアを使用して二つのノードをオンメモリで接続しています。

#### アプリケーションの実装

アプリケーションは各ノードでどのようなサービスを公開するかを決めインターフェースを定義します。
ワイヤーが接続されたときにどちらがどういったアクションを取るかプロトコルを決めます。
クライアント証明書の発行や管理手順を決めます。

アプリケーションが実装で意識しなければならないこと

1. ノードが公開するサービスのインターフェース定義とその実装。
1. メッセージングを行う場合、ファンクション呼び出し中のブロック送受信、または入出力ストリームの使用。
1. ノードを使用した接続または接続の受け付けを実装。

#### コンポーネントの構成

以下に asterisque で使用するクラスの構成を挙げます。

![dir](images/biRPC-ClassChart.png)

### <a name="ServiceImplementationAndRemoteCall"></a>サービスの実装と呼び出し

#### インターフェースによるサービス実装と呼び出し

* [サンプルコード](https://github.com/torao/asterisk/blob/master/src/test/scala/sample/Sample1.scala)

asterisque での RPC はクライアント/サーバで共有されるインターフェース (`trait`) に基づいて実装することが出来ます。この方法は後述する DSL による実装より厳密で静的型付き言語と親和性が良いため例の多くはこちらの方法を使用します。

インターフェースによるサービス実装の例として `greeting()` というリモート呼び出し可能なメソッドを一つだけ持つ trait を考えます。

```scala
trait GreetingService {
  @Export(10)
  def greeting(name:String):Future[String]
}
```

`@Export(10)` はこのメソッドがリモート呼び出し可能であり、そのファンクション番号として 10 を割り当てられている事を示しています。ファンクション番号は公開中のサービスで機能を識別するための short 値です。同一の trait 内でユニークな番号を割り当てる必要があります。

もう一つの重要な要件として `@Export` によって公開されたメソッドは `scala.concurrent.Future` の返値を宣言する必要があります。

ファンクションの実装側ではファンクションの実行スレッドははノードで共有されており、ファンクション内で時間のかかる処理を行うとそのノードのすべての呼び出しが影響を受けます。このため I/O ウェイトなどの時間のかかる処理を行う場合は `scala.concurrent.future` などを使用して非同期化しすぐに終了する必要があります。

また asterisque は非同期メッセージングに基づいて実装されているためクライアント側でも `Future` で非同期に結果を受け取る事が出来ます (`Future` は `Await` を使用することで同期化を選択的に使用できます)。

上記の trait は `Service` クラスと併せて以下のように実装できます。

```scala
class GreetingServiceImpl extends Service with GreetingService {
  def greeting(name:String):Future[String] = Promise.successful(s"hello, $name").future
}
```

もし時間のかかる処理を行うのであれば並行処理を使用して以下のように実装できます。

```scala
def greeting(name:String):Future[String] = scala.concurrent.future {
  Thread.sleep(3 * 1000)  // 時間のかかる処理…
  s"hello, $name"
}
```

サーバ側ノード作成時にこのサービスを指定し、クライアント側ノードからセッション確立後に呼び出します。このとき `Session.bind()` を用いてリモートインターフェースに対するスケルトン (動的プロキシ) を取得することでシームレスな呼び出しが可能です。

```scala
val server = Node("server").serve(new GreetingServiceImpl()).build()
server.listen(new InetSocketAddress("localhost", 5330), None)

val client = Node("client").build()
client.connect(new InetSocketAddress("localhost", 5330), None).onComplete{
  case Success(session) =>
    val service = session.bind(classOf[GreetingService])
    service.greeting("asterisque").andThen {
      case Success(result) => System.out.println(result)
      case Failure(ex) => ex.printStackTrace()
    }.onComplete { _ =>
      server.shutdown()
      client.shutdown()
		}
  case Failure(ex) => ex.printStackTrace()
}
```

上記は `Await` を使用することでクライアント側を同期実装することができます。

```scala
val future1 = client.connect(new InetSocketAddress("localhost", 5330), None)
val session = Await.result(future1, Duration.Inf)
val service = session.bind(classOf[GreetingService])
val future2 = service.greeting("asterisque")
val result = Await.result(future2, Duration.Inf)
System.out.println(result)
server.shutdown()
client.shutdown()
```

#### ファンクション番号によるサービス実装と呼び出し

* [サンプルコード](https://github.com/torao/asterisk/blob/master/src/test/scala/sample/Sample2.scala)

インターフェースを使用する代わりにファンクション番号とその実装を直接結合してサービスを実装することが出来ます。

```scala
class GreetingServiceImpl extends Service {
  10 accept { args => Promise.successful(s"hello, ${args(0)}").future }
}
```

クライアント側でもセッションにファンクション番号を指定してリモートメソッドを呼び出すことが出来ます。

```scala
val pipe = session.open(10).call("asterisque")
pipe.future.onComplete{
  case Success(result) => System.out.println(result)
  case Failure(ex) => ex.printStackTrace()
}
```

#### メッセージングによる非同期メッセージパッシングとストリーミング

* [サンプルコード](https://github.com/torao/asterisk/blob/master/src/test/scala/sample/Sample3.scala)

TODO: ソース未検証

非同期でのブロックの受信は送信よりもセンシティブです。pipe が構築され、受信のためのハンドラをアプリケーションが設定する前に受信したブロックは取りこぼしとなります。パイプ構築後にハンドラを設定する設計ではそれまでの受信ブロックをキューに保存しておかなければならない。あるいは、パイプ構築より先にハンドラを指定しなければならない。Asterisque は後者を採用しています。

```scala
// 受信するすべての整数値を加算
10 accept { args =>
  Pipe.orElse(0){ pipe =>
    pipe.blocks.map { b => new String(b.payload, b.offset, b.length).getInt }.sum
  }
} stream true

// 受信するすべてのブロックをそのまま返す
@Export(value=20,stream=true)
def messaging():Future[Unit] = Pipe.orElse(()){ pipe =>
  val promise = Promise[Unit]()
  pipe.blocks.foreach { block =>
    if(! block.isEOF){
      pipe.blocks.send(block.payload, block.offset, block.length)
    } else {
      pipe.blocks.sendEOF()
      promise.successful(())
    }
  }
  promise.future
}
```

1. このファンクションがメッセージングを使用する宣言として stream(true) を指定する。指定を忘れると受信したブロックは破棄されます。
1. Pipe はファンクションを呼び出したスレッド内で取得する。`future` や別スレッド内からは参照不可能。
1. pipe.blocks に指定する繰り返し処理・集約関数はブロックを受信したときに非同期で呼び出される。

```scala
// "1","2","3","4" を送信 / 受信したデータを出力
def exec(pipe:Pipe):Unit = {
	pipe.blocks.foreach{ b => println(new String(b.payload, b.offset, b.length))
	Seq(1, 2, 3, 4).map{ n => n.toString.getBytes }.foreach{ b => pipe.blocks.send(b) }
	pipe.blocks.sendEOF()
	pipe.future.onSuccess {
		case Success(result) => System.out.println(s"Result: $result")
		case Failure(ex) => ex.printStackTrace()
	}
}
session.openWithStream(10).onComplete{
  case Success(pipe) => exec(pipe)            // Result: 10
  case Failure(ex) => ex.printStackTrace()
}
session.openWithStream(20).onComplete{
  case Success(pipe) => exec(pipe)            // 1\n2\n3\n4\nResult: ()
  case Failure(ex) => ex.printStackTrace()
}
```

### サーバ実装

### クライアント実装

### 転送可能なデータタイプ

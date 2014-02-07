## Developer Guide

### イントロダクション

#### 用語と構成

<dl>
<dt>Node</dt>
<dd>Asterisque における通信端点を表します。Node は任意の Node と接続することでネットワークを構築します。</dd>
<dt>Service</dt>
<dd>ノード上に存在し別のノードからの要求に応じて処理を提供します。セッション発生後に変更可能です。</dd>
<dt>Bridge</dt>
<dd>Wire を生成します。</dd>
<dt>Wire</dt>
<dd>ノード間を接続するための通信の実装です。下層の通信レイヤーと Message のやりとりを実装します。</dd>
<dt>Codec</dt>
<dd>メッセージのシリアライズを行う処理。</dd>
</dl>

![Network](images/biRPC-NodeNetwork.png)

<dl>
<dt>Session</dt>
<dd>Node 間の接続に対して発生します。TCP における接続 (java での Socket) と同じスコープを持ちます。</dd>
<dt>Pipe</dt>
<dd>リモート処理の呼び出しで発生します。2つの処理を接続します。Open メッセージから Close メッセージまでのスコープを持ちます。</dd>
</dl>

![dir](images/biRPC-Bidirection.png)

<dl>
<dt>Message</dt>
<dd>Node 間でやり取りされるデータ単位です。Open, Close, Block の 3 種類が存在します。</dd>
</dl>

### サービスの実装と呼び出し

#### インターフェースによるサービス実装と呼び出し

Asterisque での RPC はクライアント/サーバで共有されるインターフェース (`trait`) に基づいて実装することが出来ます。この方法は後述する DSL による実装より厳密で静的型付き言語と親和性が良いため例の多くはこちらの方法を使用します。

インターフェースによるサービス実装の例として `greeting()` というリモート呼び出し可能なメソッドを一つだけ持つ trait を考えます。

```scala
trait GreetingService {
  @Export(10)
  def greeting(name:String):Future[String]
}
```

`@Export(10)` はこのメソッドがリモート呼び出し可能であり、そのファンクション番号として 10 を割り当てられている事を示すアノテーションです。ファンクション番号は公開中のサービスで機能を識別するための short 値であり、同一の trait 内でユニークな番号を割り当てる必要があります。

もう一つの重要な条件として `@Export` によって公開されたメソッドは `scala.concurrent.Future` の返値を宣言する必要があります。

サービスのメソッドを呼び出すスレッドは Node インスタンスで共有されており、メソッド内で時間のかかる処理を行うとその Node インスタンスに対するすべての呼び出しが影響を受けます。このため I/O ウェイトなどの時間のかかる処理を行う場合は `scala.concurrent.future` などを使用して非同期化しすぐに終了する必要があります。

また Asterisque は非同期メッセージングに基づいて実装されているためクライアント側でも `Future` で非同期に結果を受け取る事が出来ます (`Future` は `Await` を使用することで同期化を選択的に使用できます)。

上記の trait は `Service` クラスと併せて以下のように実装できます。

```scala
class GreetingServiceImpl extends Service with GreetingService {
  def greeting(name:String):Future[String] = Promise.successful(s"hello, $name").future
}
```

もし時間のかかる処理を行うのであればメソッドは以下のように実装できます。

```scala
def greeting(name:String):Future[String] = scala.concurrent.future {
  Thread.sleep(3 * 1000)  // 時間のかかる処理…
  s"hello, $name"
}
```

サーバ側ノード作成時にこのサービスを指定し、クライアント側ノードからセッション確立後に呼び出します。このとき `Session.bind()` を用いてリモートインターフェースに対するスケルトン (動的プロキシ) を取得することでシームレスな呼び出しが可能です。

```scala
val server = Node("server").serve(new GreetingServiceImpl()).build()
server.listen(new InetSocketAddress("localhost", 5330), None){ _ => None }

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

インターフェースを使用する代わりにファンクション番号とその実装を直接結合してサービスを実装することが出来ます。

```scala
class GreetingServiceImpl extends Service {
  10 accept { args => Promise.successful(s"hello, ${args(0)}").future }
}
```

クライアント側でもセッションにファンクション番号を指定してリモートメソッドを呼び出すことが出来ます。

```scala
session.open(10).onSuccess{ result =>
  System.out.println(result)
}.call("asterisque")
```

### サーバ実装


### クライアント実装

### メッセージング

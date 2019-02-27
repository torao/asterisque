# Implementation Details

## Low-Level Interface

### Protocol Service Provider

asterisque は標準で WebSocket をベースにした通信プロトコルを実装しています。低レベル通信レイヤーは `Bridge` インターフェースに
よって抽象化されており、Java の Service Provider Interface を使用して独自の `Bridge` を実装することができます。

アプリケーションまたはミドルウェアベンダーは独自の `Bridge` を実装し:

```java
package com.mybridge;
import io.asterisque.wire.gateway.Bridge;
public class MyBridge implements Bridge {
  // ...
}
```

リソースファイル `META-INF/services/io.asterisque.wire.gateway.Bridge` に実装クラス名を記述します。改行または空白で
区切ることによって複数の実装クラスを指定することができます。

```
com.mybridge.MyBridge
```

JAR ファイルとして配布する場合はブートストラップ時のライブラリに追加することで利用することができます。

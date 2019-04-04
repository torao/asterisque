# Messaging Protocol and Format

すべてのメッセージのバイナリ表現は TLV (Type-Length-Value) 形式です。1 バイトの type、2 バイトの Length とそれに続く length - 3 バイトの message body で構成されます。
message body にどのようなデータが含まれるかは type の値に依存します。

```
+--------+--------+--------+--------+--------+~~~+
| type   | length          | message_body        |
+--------+--------+--------+--------+--------+~~~+
```

| Fields         | Type           | Description                           |
|:---------------|:---------------|:--------------------------------------|
| `type`         | UINT8          | type identifier of this message       |
| `length`       | UINT16         | number of bytes of the entire message |
| `message_body` | BYTE[length-3] | message body                          |

`length` の幅が示すようにメッセージ全体の長さは 65,535 バイトが上限となります。

## Object Format



## Open Message

Open は `type=0x28` (`'('`) で示されるメッセージです。
双方で新しくパイプを作成し処理を開始するために使用されます。

| Fields        | Type    | Description |
|:--------------|:--------|:------------|
| `pipe_id`     | 'UINT16 | |
| `function_id` | 'UINT16 | |
| `bits`        | 'UINT8  | |
| `param_size`  | 'UINT8  | |
| `params`      | 'BINARY | |

`bits` フィールドは以下の通り。

| Fields     | Bitwidth | Description |
|:-----------|:---------|:------------|
| `reserved`  | 4       | ignored but all bits must be 0 |
| `priority`  | 4       | priority of function call represented by -8 to +7 |

セッションを開始した secondary ピアが primary ピアのファンクションを呼び出すとき `pipe_id` の最上位ビットは 0 になります。
その逆方向、primary ピアが secondary ピアのファンクションを呼び出すとき `pipe_id` の最上位ビットは 1 になります。
この取り決めにより双方のピアが重複しない `pipe_id` を生成することができます。

`priority == bits & 0xF` は Open によって行われる処理の、同一セッション内での優先度を 8 段階 [-8, 7] の数値で表します。
サービス提供側はこの優先度を参考に優先度の重み付けを行うことができます。

## Close Message

Close は `type=0x29` (`')'`) で示されるメッセージです。
Open によって開始したパイプをクローズし双方の処理を終了するために使用されます。

| Fields    | Type   | Description |
|:----------|:-------|:------------|
| `pipe_id` | UINT16 | destination pipe id |
| `bits`    | UINT8  | |
| `result`  | BINARY | |

`bits` フィールドは以下の通り。

| Fields     | Bitwidth | Description |
|:-----------|:---------|:------------|
| `reserved` | 7        | ignored but all bits must be 0 |
| `success`  | 1        | 1 if the `result` contains valid result, otherwise 0 and `result` is a UTF-8 string indicating an error |

`success == bits & 1` が 1 の場合、`result` バイナリには Open-Close によって行われた処理に対する有効な結果が serialize objects として含まれています。0 の場合、`result` はエラー状況を説明する UTF-8 文字列です。

## Block Message

Block は `type=0x23` (`'#'`) で示されるメッセージです。
Block メッセージは Open から Close の間に 1 つ以上のバルクデータを送受信するために使用されます。

| Fields    | Type   | Description |
|:----------|:-------|:------------|
| `pipe_id` | UINT16 | destination pipe id          |
| `bits`    | UINT8  | control fields of this block |
| `payload` | BINARY | bulk data                    |

`bits` フィールドは以下の通り。

| Fields      | Bitwidth | Description |
|:------------|:---------|:------------|
| `eof`       | 1        | 1 if this is the last block of stream, otherwise the following block exists |
| `loss_rate` | 7        | probability that this may be discarded by a relay, 0 for non-less, 127 if discarded 100% |

`eof = (bits >> 6) & 1` が 1 の場合、パイプ上の Block 送信方向でそれ以上のブロック送信が発生しないことを示しています。

`loss_rate` はこの Block Message を中継者が破棄して良いかの参考値を示しています。
`loss_rate=0` はいかなる中継車も破棄してはならないことを示し、`loss_rate=127` は 100% の確率で廃棄しても構わないことを示しています。
このフィールドはあまり重要でないデータや映像のような劣化可能なデータをストリーミングすることを想定しています。

中継者は `eof=1` の付与された Block を破棄する場合、それより前でストリームの最後に相当する Block メッセージに `eof=1` を付与しなければいけません。

## Control Message

Control は `type=0x2A` (`'*'`) で示されるメッセージです。
セッション開始時の設定の同期やセッション終了などの制御処理を行うために使用します。

Control メッセージは 1 バイトの `code` とそれに続くバイナリで構成されており、`code` の値によってバイナリの意味が変わります。

### Sync Session

Sync Session 制御メッセージはセッション開始直後に双方で交換するメッセージです。

| Fields        | Type               | Description |
|:--------------|:-------------------|:------------|
| `code`        | UINT8              | `'Q'` |
| `version`     | UINT32             | 0x0100 |
| `sealed_cert` | BINARY             | |
| `service_id`  | STRING             | |
| `utc_time`    | UINT64             | |
| `config`      | MAP[STRING,STRING] | |

通信相手の身元を保証する証明書の交換は、セッション開始時の SSL/TLS や、Sync Session 後の証明書交換シーケンスによって行われます。

`cert` は Envelope フォーマットの

| Fields      | Type | Description |
|:------------|:-------|:---------|
| `payload`   | BINARY |
| `sign_type` | UINT8 |
| `sign`      | BINARY |
| `signer`    | STRING |

### Close Session

Close Session 制御メッセージはセッションを終了するとき (通常は TCP 的な切断を意味する) に使用します。
データは付属せず、存在する場合は無視しなければいけません。

| Fields | Type  | Description |
|:-------|:------|:------------|
| `code` | UINT8 | `'C'`       |



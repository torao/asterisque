## P2P ネットワークへの参加

ユーザやノードはネットワーク認証局から発行された証明書を使用して P2P ネットワークに参加することができます。このスキームは SSL/TLS 通信を行うために CA からサーバ証明書の発行を受ける手順と同じです。

### ノードの登録

新しいノードが P2P ネットワークに参加するまでの手順を説明します。

ノードが使用する ECDSA 鍵を作成します。

```
$ openssl ecparam -genkey -name prime256v1 -noout -out node.pem
$ cat node.pem
-----BEGIN EC PRIVATE KEY-----
MHcCAQEEIC7gU15pTpF6a8cJCVHqxt1sJmMdlFV/axZJtRxF0K4poAoGCCqGSM49
...
-----END EC PRIVATE KEY-----

$ chmod 600 node.pem
```

次にこの鍵から CSR を作成します。入力パラメータは P2P ネットワークの管理ポリシーに依存します。

```
$ openssl req -new -sha256 -key keypair.pem -out node.csr
You are about to be asked to enter information that will be incorporated
into your certificate request.
What you are about to enter is what is called a Distinguished Name or a DN.
There are quite a few fields but you can leave some blank
For some fields there will be a default value,
If you enter '.', the field will be left blank.
-----
Country Name (2 letter code) [AU]:JP
State or Province Name (full name) [Some-State]:Tokyo
Locality Name (eg, city) []:Sumida
Organization Name (eg, company) [Internet Widgits Pty Ltd]:Karillon Co, Ltd.
Organizational Unit Name (eg, section) []:P2P Lab
Common Name (e.g. server FQDN or YOUR name) []:6d0af028-2b8d-4e13-8913-47bcbbc241cc
Email Address []:6d0af028-2b8d-4e13-8913-47bcbbc241cc@karillon.com

Please enter the following 'extra' attributes
to be sent with your certificate request
A challenge password []:
An optional company name []:
```

> この入力値はノード証明書に記載され P2P ネットワークで公開・共有される。従ってプライバシー情報を含まないようなポリシーを設計することが推奨される。

できあがった CSR ファイルを P2P ネットワークのネットワーク認証局に提出すれば、ノード証明書が送り返されるでしょう。

### ルート証明書のインストール

ネットワーク管理者より取得したルート CA 証明書を `$APP_ROOT/conf/authority/trusted` に保存する。

```
$ export APP_ROOT=`pwd`
$ mkdir -p $APP_ROOT/ca/root
$ cd $APP_ROOT/certs/trusted/root
$ openssl 
```

### TestNet

自己署名 CA 証明書の作成

```
openssl x509 -days 3650 -in ca.csr -req -signkey keypair.pem -out ca.crt

```
# Type Conversion

## Supported Types

asterisque は Open 及び Close メッセージ (function パラメータ/結果) に任意の型を持つデータを使用する事が出来る。型はコンパイラに最適化のためのヒントを与え予期しないバグからプログラマを助ける有用な情報であるが、一方で言語境界、システム境界での変換は煩わしいものである。asterisque ではこれらのデータ/型変換のために拡張可能なフレームワークを使用できる。

メッセージの Marshall で表現できるのは以下の 1 値 13 型である。それぞれに対して各言語バインディングごとに言語固有の型との相互変換を定義する。どのような型が相互変換可能かは言語バインディングの実装依存である。

| *Q Type | Java/Scala Primary Type     | Java Compatibility | Scala Compatibility |
|:--------|-----------------------------|--------------------|---------------------|
| void    | java.lang.Void (null)       |                    | scala.Unit, scala.runtime.BoxedUnit (()) |
| boolean | java.lang.Boolean           |                    | scala.Boolean       |
| int8    | java.lang.Byte              |                    | scala.Byte          |
| int16   | java.lang.Short             |                    | scala.Short         |
| int32   | java.lang.Integer           |                    | scala.Int           |
| int64   | java.lang.Long              |                    | scala.Long          |
| float16 | java.lang.Float             |                    | scala.Float         |
| float32 | java.lang.Double            |                    | scala.Double        |
| binary  | byte[]                      |                    | scala.Array[Byte]   |
| string  | java.lang.String            | java.lang.Character, char[] | scala.Char, scala.Array[Char] |
| uuid    | java.util.UUID              |                    |                     |
| list    | java.util.List<?>           | java.util.Set&lt;?&gt;, byte以外の配列 | scala.Seq[_] (List[_]), scala.Set[_], scala.Unit,Array[Any] |
| map     | java.util.Map&lt;?,?&gt;          |                    | scala.Map[_,_]      |
| struct  | org.asterisque.codec.Struct |                    | scala.Product       |

## Null Conversions

asterisque では値がないことを示すために null 値を使用することが出来る。もし null safe や maybe モナドの導入が必要であれば、それは各言語バインディングによって実装される。

Java, Scala のプリミティブ型フィールドに null 値が検出された場合は以下のように解釈される。

| Type    | Value    |
|:--------|----------|
| boolean | false    |
| byte    | (byte)0  |
| short   | (short)0 |
| int     | 0        |
| long    | 0L       |
| float   | 0.0f     |
| double  | 0.0      |

## Extend Type Conversion

function 入出力の型変換にアプリケーションやフレームワーク固有の型変換を追加することが出来る。拡張の変換処理は変換元のデータを転送可能な型のいずれかに変換し、またそれから元のデータを復元する処理を定義する。具体的には変換処理を実装した TypeConversion サブクラスを作成し TypeConversion.addExtension() で指定する。

言語バインディングやフレームワークで行う。

```
class MyTypeConversion extends TypeConversion {
  public MyTypeConversion(){
    setFromTo(MyClass.class, String.class, MyClass::toLong, MyClass::fromLong);
    setFromTo(YourClass.class, byte[].class, YourClass::toByteArray, MyClass::parse);
  }
}
...
TypeConversion.addExtension(new MyTypeConversion());
```

TypeConversion のインスタンスはスレッドセーフでなければいけない。このため型変換の定義はコンストラクタで行う必要があり、動的な状態を持つべきではない。
ある型または値が addExtension() を行った複数の拡張変換と一致する場合、後に追加されたものが優先される。
このためデフォルトの変換をアプリケーションで上書きすることが出来る。

`setFromTo()` の第二パラメータは転送可能型でなければならない。

## Tuple

転送可能な型の組み合わせで表現できる場合は Tuple を使用する事が出来る。
Struct は 1)インデックスでフィールドにアクセスでき 2)スキームがクラス名を示し 3)出現順序のコンストラクタで復元できる

```
public class Font extends Struct {
  public final String family;
  public final int size;
  public final int style;
  public Font(String family, int size, int style){
    this.family = family;
    this.size = size;
    this.style = style;
  }
  public String name(){ return getClass().getName(); }
  public int count(){ return 3; }
  public Object element(int i){
    switch(i){
      case 0: return family;
      case 1: return size;
      case 2: return style;
  }
}
```

Tuple はシリアライズ時にインスタンスのクラス名が保持されます。
シリアライズされた Tuple の復元型が元のインスタンスのスーパークラスであった場合、元のクラスのインスタンスが復元されます。

```
public class A implements Tuple { ... }
public class B extends A { ... }

foo(new B());

public foo(A a){
  if(a instanceof B) System.out.println("Wow!");  // Wow!
}
```

メソッド宣言または返値に Tuple 型を使用すればどのような Tuple でも受け取ることが出来ます。
package io.asterisque.core;

import javax.annotation.Nullable;
import java.util.Objects;

// This class is auto-generated by script/tuple.scala, DON'T EDIT
public final class Tuple4<T1, T2, T3, T4> extends Tuple {
  public final T1 _1;
  public final T2 _2;
  public final T3 _3;
  public final T4 _4;

  Tuple4(T1 t1, T2 t2, T3 t3, T4 t4) {
    super(new Object[]{t1, t2, t3, t4});
    this._1 = t1;
    this._2 = t2;
    this._3 = t3;
    this._4 = t4;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this._1, this._2, this._3, this._4);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if(!(other instanceof Tuple4)) return false;
    final Tuple4 tuple = (Tuple4)other;
    return Objects.equals(this._1, tuple._1) && Objects.equals(this._2, tuple._2) && Objects.equals(this._3, tuple._3) && Objects.equals(this._4, tuple._4);
  }
}
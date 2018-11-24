package io.asterisque.core;

import javax.annotation.Nullable;
import java.util.Objects;

// This class is auto-generated by script/tuple.scala, DON'T EDIT
public final class Tuple15<T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15> extends Tuple {
  public final T1 _1;
  public final T2 _2;
  public final T3 _3;
  public final T4 _4;
  public final T5 _5;
  public final T6 _6;
  public final T7 _7;
  public final T8 _8;
  public final T9 _9;
  public final T10 _10;
  public final T11 _11;
  public final T12 _12;
  public final T13 _13;
  public final T14 _14;
  public final T15 _15;

  Tuple15(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6, T7 t7, T8 t8, T9 t9, T10 t10, T11 t11, T12 t12, T13 t13, T14 t14, T15 t15) {
    super(new Object[]{t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15});
    this._1 = t1;
    this._2 = t2;
    this._3 = t3;
    this._4 = t4;
    this._5 = t5;
    this._6 = t6;
    this._7 = t7;
    this._8 = t8;
    this._9 = t9;
    this._10 = t10;
    this._11 = t11;
    this._12 = t12;
    this._13 = t13;
    this._14 = t14;
    this._15 = t15;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this._1, this._2, this._3, this._4, this._5, this._6, this._7, this._8, this._9, this._10, this._11, this._12, this._13, this._14, this._15);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if(!(other instanceof Tuple15)) return false;
    final Tuple15 tuple = (Tuple15)other;
    return Objects.equals(this._1, tuple._1) && Objects.equals(this._2, tuple._2) && Objects.equals(this._3, tuple._3) && Objects.equals(this._4, tuple._4) && Objects.equals(this._5, tuple._5) && Objects.equals(this._6, tuple._6) && Objects.equals(this._7, tuple._7) && Objects.equals(this._8, tuple._8) && Objects.equals(this._9, tuple._9) && Objects.equals(this._10, tuple._10) && Objects.equals(this._11, tuple._11) && Objects.equals(this._12, tuple._12) && Objects.equals(this._13, tuple._13) && Objects.equals(this._14, tuple._14) && Objects.equals(this._15, tuple._15);
  }
}

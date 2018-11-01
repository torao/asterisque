/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk;

import scala.None$;
import scala.Option;
import scala.Some;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// S
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public final class S {
	public static <T> Option<T> Some(T value){
		@SuppressWarnings("unchecked")
		Option<T> some = Option.class.cast(new Some(value));
		return some;
	}
	public static <T> Option<T> None(){
		@SuppressWarnings("unchecked")
		Option<T> none = (Option<T>)NONE;
		return none;
	}
	private static final Option<?> NONE = Option.class.cast(None$.MODULE$);
}

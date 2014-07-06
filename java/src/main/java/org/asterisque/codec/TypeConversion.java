/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// TypeConversion
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * API 呼び出し時のパラメータや返値を asterisque で転送可能な型へ変換したり、転送可能な型から実際に API で使用
 * されている型に逆変換を行うクラスです。{@link #addExtension(TypeConversion)} を使用してアプリケーションや
 * フレームワーク、実行環境固有の型変換を追加することが出来ます。
 *
 * @author Takami Torao
 */
public abstract class TypeConversion {
	private static final Logger logger = LoggerFactory.getLogger(TypeConversion.class);

	// ==============================================================================================
	// 転送可能型変換
	// ==============================================================================================
	/**
	 * 特定の値に対する転送可能型への変換処理です。
	 */
	private final List<Function<Object,Optional<Object>>> safeValue = new ArrayList<>();

	// ==============================================================================================
	// 転送可能型変換
	// ==============================================================================================
	/**
	 * 特定の型に対する転送可能型への変換処理です。挿入順を維持するために LinkedHashMap を使用します。
	 */
	private Map<Class<?>, Function<?,?>> safe = new LinkedHashMap<>();

	// ==============================================================================================
	// API 呼び出し型変換
	// ==============================================================================================
	/**
	 * 転送可能型に対する API 呼び出し型への変換処理です。
	 */
	private Map<Class<?>, Map<Class<?>, Function<?,?>>> unsafe = new HashMap<>();

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * サブクラス化して構築を行います。
	 */
	protected TypeConversion(){}

	// ==============================================================================================
	// 転送可能型変換の定義
	// ==============================================================================================
	/**
	 * 指定されたクラスから転送可能な型へ変換する処理を定義します。
	 * サブクラスのコンストラクタによって呼び出されます。
	 */
	protected <FROM,TO> void setTransferConversion(Class<FROM> type, Function<FROM, TO> convert){
		safe.put(type, convert);
	}

	// ==============================================================================================
	// 転送可能型変換の定義
	// ==============================================================================================
	/**
	 * 指定されたクラスから転送可能な型へ変換する処理を定義します。
	 * サブクラスのコンストラクタによって呼び出されます。
	 */
	protected void setTransferConversion(Function<Object,Optional<Object>> convert){
		safeValue.add(convert);
	}

	// ==============================================================================================
	// API 呼び出し型変換の定義
	// ==============================================================================================
	/**
	 * 転送可能型から指定された API 呼び出し型へ変換する処理を定義します。
	 * サブクラスのコンストラクタによって呼び出されます。
	 */
	protected <FROM,TO> void setMethodCallConversion(Class<FROM> from, Class<TO> to, Function<FROM, TO> reverse){
		if(! isDefaultSafeType(from)){
			throw new IllegalArgumentException(String.format("%s is not a transfer safe type", to.getName()));
		}
		Map<Class<?>, Function<?,?>> f = unsafe.get(from);
		if(f == null){
			f = new HashMap<>();
			unsafe.put(from, f);
		}
		f.put(to, reverse);
	}

	// ==============================================================================================
	// API 呼び出し型変換の定義
	// ==============================================================================================
	/**
	 * 転送可能型への変換とその逆変換を同時に指定します。
	 * サブクラスのコンストラクタによって呼び出されます。
	 */
	protected <FROM,TO> void setFromTo(Class<FROM> from, Class<TO> to, Function<FROM, TO> convert, Function<TO, FROM> reverse){
		setTransferConversion(from, convert);
		setMethodCallConversion(to, from, reverse);
	}

	// ==============================================================================================
	// 転送可能型変換
	// ==============================================================================================
	/**
	 * このインスタンスの定義で指定された値を転送可能な型に変換します。
	 */
	private Optional<Object> _toTransfer(Object value){
		Optional<Function<Object,Object>> convert = _find(value.getClass(), safe);
		if(convert.isPresent()){
			try {
				return Optional.of(convert.get().apply(value));
			} catch(RuntimeException ex){
				logger.debug(String.format("safe-transfer conversion from %s failure: %s", value.getClass().getSimpleName(), ex));
				return Optional.empty();
			}
		}
		for(Function<Object,Optional<Object>> f: safeValue){
			Optional<Object> conv = f.apply(value);
			if(conv.isPresent()){
				return conv;
			}
		}
		return Optional.empty();
	}

	private static final Optional<Object> Null = Optional.of(new Object());

	// ==============================================================================================
	// API 呼び出し型変換
	// ==============================================================================================
	/**
	 * このインスタンスの定義で指定された転送可能型の値を API 呼び出し用の値に変換します。
	 */
	private <T> Optional<T> _toMethodCall(Object value, Class<T> type){
		if(value == null || type.isAssignableFrom(value.getClass())){
			return Optional.of(type.cast(value));
		}
		Map<Class<?>, Function<?,?>> t = unsafe.get(value.getClass());
		if(t == null){
			return Optional.empty();
		}
		@SuppressWarnings("unchecked")
		Function<Object,Object> reverse = (Function<Object,Object>)t.get(type);
		if(reverse == null){
			return Optional.empty();
		}
		try {
			return Optional.of(type.cast(reverse.apply(value)));
		} catch(RuntimeException ex){
			logger.debug(String.format("method-call conversion from %s to %s failure: %s",
				value.getClass().getSimpleName(), type.getSimpleName(), ex));
			return Optional.empty();
		}
	}

	// ==============================================================================================
	// 型に対する値参照
	// ==============================================================================================
	/**
	 * 指定された型から変換可能な型の値を参照します。
	 */
	private static Optional<Function<Object,Object>> _find(Class<?> clazz, Map<Class<?>,Function<?,?>> map){
		Optional<Class<?>> regular = getCompatibleType(clazz, map.keySet());
		if(regular.isPresent()) {
			@SuppressWarnings("unchecked")
			Function<Object,Object> value = (Function<Object,Object>)map.get(regular.get());
			assert(value != null);
			return Optional.of(value);
		} else {
			return Optional.empty();
		}
	}

	// ==============================================================================================
	// 型に対する値参照
	// ==============================================================================================
	/**
	 * 指定された型から変換可能な型の値を参照します。
	 */
	private static Set<Class<?>> _getClasses(Class<?> clazz){
		if(clazz == null){
			return new HashSet<>();
		} else {
			Set<Class<?>> set = _getClasses(clazz.getSuperclass());
			set.add(clazz);
			Stream.of(clazz.getInterfaces()).forEach( c -> set.add(c) );
			return set;
		}
	}

	// ==============================================================================================
	// 変換処理
	// ==============================================================================================
	/**
	 * システム標準の変換処理です。後に定義された変換定義が先頭に格納されています。
	 */
	private static AtomicReference<List<TypeConversion>> SystemConversions = new AtomicReference<>(new ArrayList<>());

	// ==============================================================================================
	// 変換定義の拡張
	// ==============================================================================================
	/**
	 * 実行環境のアプリケーションやフレームワーク、言語などによる追加の変換処理を定義します。このメソッドで指定した
	 * 変換定義は既存の定義より優先されます。
	 */
	public static void addExtension(TypeConversion ext){
		List<TypeConversion> oldList = SystemConversions.get();
		List<TypeConversion> newList = new ArrayList<>(oldList);
		newList.add(0, ext);
		if(! SystemConversions.compareAndSet(oldList, newList)){
			addExtension(ext);
		}
	}

	// ==============================================================================================
	// 転送可能型変換
	// ==============================================================================================
	/**
	 * 指定された値を転送可能な型に変換します。
	 * @throws org.asterisque.codec.CodecException 転送可能な型に変換できなかった場合
	 */
	public static Object toTransfer(Object value){
		if(isDefaultSafeValue(value)){
			return value;
		}
		for(TypeConversion tc: SystemConversions.get()){
			Optional<Object> t = tc._toTransfer(value);
			if(t.isPresent()){
				Object result = t == Null? null: t.get();
				assert(isDefaultSafeValue(result)): value + " -> " + result;
				return result;
			}
		}
		throw new CodecException(String.format("unsupported type conversion to transfer: from %s: (%s)",
			(value.getClass() == null)? "null": value.getClass().getSimpleName(),
			_getClasses(value.getClass()).parallelStream().map(Class::getCanonicalName).collect(Collectors.joining(", "))));
	}

	// ==============================================================================================
	// API 呼び出し型変換
	// ==============================================================================================
	/**
	 * 指定された転送可能型の値を API 呼び出し用の値に変換します。
	 * @throws org.asterisque.codec.CodecException 変換できなかった場合
	 */
	public static <T> T toMethodCall(Object value, Class<T> type){
		for(TypeConversion tc: SystemConversions.get()){
			Optional<T> t = tc._toMethodCall(value, type);
			if(t.isPresent()){
				return t.get();
			}
		}
		throw new CodecException(String.format("unsupported type conversion for method-call: from %s to %s",
			value == null? "null": value.getClass().getName(), type.getName()));
	}

	// ==============================================================================================
	// 転送可能型
	// ==============================================================================================
	/**
	 * 転送可能な型のセットです。型判定のために使用します。
	 */
	private static final Set<Class<?>> SafeType;

	static {

		// 転送可能型を定義
		Set<Class<?>> types = new HashSet<>();
		types.add(Boolean.class);
		types.add(Byte.class);
		types.add(Short.class);
		types.add(Integer.class);
		types.add(Long.class);
		types.add(Float.class);
		types.add(Double.class);
		types.add(byte[].class);
		types.add(String.class);
		types.add(UUID.class);
		types.add(List.class);
		types.add(Map.class);
		types.add(Struct.class);
		SafeType = Collections.unmodifiableSet(types);

		// デフォルトの変換定義を追加
		addExtension(new DefaultConversion());
	}

	// ==============================================================================================
	// 転送可能判定
	// ==============================================================================================
	/**
	 * 指定された値が転送可能かを判定します。
	 */
	public static boolean isDefaultSafeValue(Object value){
		return (value == null) || isDefaultSafeType(value.getClass());
	}

	// ==============================================================================================
	// 転送可能型判定
	// ==============================================================================================
	/**
	 * 指定された型が転送可能かを判定します。
	 */
	public static boolean isDefaultSafeType(Class<?> type){
		return (type == null) || getCompatibleType(type, SafeType).isPresent();
	}

	// ==============================================================================================
	// 転送可能型判定
	// ==============================================================================================
	/**
	 * 指定された型が転送可能かを判定します。
	 */
	private static Optional<Class<?>> getCompatibleType(Class<?> type, Set<Class<?>> set){
		if(set.contains(type)){
			return Optional.of(type);
		}
		for(Class<?> c: set){
			if(c.isAssignableFrom(type)){
				return Optional.of(c);
			}
		}
		return Optional.empty();
	}

}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// DefaultConversion
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * デフォルトの変換を定義します。
 *
 * @author Takami Torao
 */
class DefaultConversion extends TypeConversion {
	public DefaultConversion(){
		setFromTo(Character.class, String.class, String::valueOf, s -> s.length()>0? s.charAt(0): '\0' );
		setFromTo(char[].class, String.class, String::new, String::toCharArray);
		setFromTo(Object[].class, List.class, Arrays::asList, List::toArray);
		setFromTo(boolean[].class, List.class, DefaultConversion::toList, DefaultConversion::toBooleanArray);
		setFromTo(short[].class, List.class, DefaultConversion::toList, DefaultConversion::toShortArray);
		setFromTo(int[].class, List.class, DefaultConversion::toList, DefaultConversion::toIntArray);
		setFromTo(long[].class, List.class, DefaultConversion::toList, DefaultConversion::toLongArray);
		setFromTo(float[].class, List.class, DefaultConversion::toList, DefaultConversion::toFloatArray);
		setFromTo(double[].class, List.class, DefaultConversion::toList, DefaultConversion::toDoubleArray);
		setFromTo(Set.class, List.class, ArrayList::new, HashSet::new);

		setMethodCallConversion(Boolean.class, Byte.class, f -> (byte)(f? 1: 0));
		setMethodCallConversion(Boolean.class, Short.class, f -> (short)(f? 1: 0));
		setMethodCallConversion(Boolean.class, Integer.class, f -> f? 1: 0);
		setMethodCallConversion(Boolean.class, Long.class, f -> (long)(f? 1: 0));
		setMethodCallConversion(Boolean.class, Float.class, f -> (float)(f? 1: 0));
		setMethodCallConversion(Boolean.class, Double.class, f -> (double) (f ? 1 : 0));
		setMethodCallConversion(Boolean.class, String.class, Object::toString);

		setMethodCallConversion(Byte.class, Boolean.class, i -> i != 0);
		setMethodCallConversion(Byte.class, Short.class, Byte::shortValue);
		setMethodCallConversion(Byte.class, Integer.class, Byte::intValue);
		setMethodCallConversion(Byte.class, Long.class, Byte::longValue);
		setMethodCallConversion(Byte.class, Float.class, Byte::floatValue);
		setMethodCallConversion(Byte.class, Double.class, Byte::doubleValue);
		setMethodCallConversion(Byte.class, String.class, Object::toString);

		setMethodCallConversion(Short.class, Boolean.class, i -> i != 0);
		setMethodCallConversion(Short.class, Byte.class, Short::byteValue);
		setMethodCallConversion(Short.class, Integer.class, Short::intValue);
		setMethodCallConversion(Short.class, Long.class, Short::longValue);
		setMethodCallConversion(Short.class, Float.class, Short::floatValue);
		setMethodCallConversion(Short.class, Double.class, Short::doubleValue);
		setMethodCallConversion(Short.class, String.class, Object::toString);

		setMethodCallConversion(Integer.class, Boolean.class, i -> i != 0);
		setMethodCallConversion(Integer.class, Byte.class, Integer::byteValue);
		setMethodCallConversion(Integer.class, Short.class, Integer::shortValue);
		setMethodCallConversion(Integer.class, Long.class, Integer::longValue);
		setMethodCallConversion(Integer.class, Float.class, Integer::floatValue);
		setMethodCallConversion(Integer.class, Double.class, Integer::doubleValue);
		setMethodCallConversion(Integer.class, String.class, Object::toString);

		setMethodCallConversion(Long.class, Boolean.class, i -> i != 0);
		setMethodCallConversion(Long.class, Byte.class, Long::byteValue);
		setMethodCallConversion(Long.class, Short.class, Long::shortValue);
		setMethodCallConversion(Long.class, Integer.class, Long::intValue);
		setMethodCallConversion(Long.class, Float.class, Long::floatValue);
		setMethodCallConversion(Long.class, Double.class, Long::doubleValue);
		setMethodCallConversion(Long.class, String.class, Object::toString);

		setMethodCallConversion(Float.class, Boolean.class, i -> i != 0);				// TODO false になるか?
		setMethodCallConversion(Float.class, Byte.class, Float::byteValue);
		setMethodCallConversion(Float.class, Short.class, Float::shortValue);
		setMethodCallConversion(Float.class, Integer.class, Float::intValue);
		setMethodCallConversion(Float.class, Long.class, Float::longValue);
		setMethodCallConversion(Float.class, Double.class, Float::doubleValue);
		setMethodCallConversion(Float.class, String.class, Object::toString);

		setMethodCallConversion(Double.class, Boolean.class, i -> i != 0);			// TODO false になるか?
		setMethodCallConversion(Double.class, Byte.class, Double::byteValue);
		setMethodCallConversion(Double.class, Short.class, Double::shortValue);
		setMethodCallConversion(Double.class, Integer.class, Double::intValue);
		setMethodCallConversion(Double.class, Long.class, Double::longValue);
		setMethodCallConversion(Double.class, Float.class, Double::floatValue);
		setMethodCallConversion(Double.class, String.class, Object::toString);

		setMethodCallConversion(String.class, Boolean.class, Boolean::valueOf);
		setMethodCallConversion(String.class, Byte.class, Byte::valueOf);
		setMethodCallConversion(String.class, Short.class, Short::valueOf);
		setMethodCallConversion(String.class, Integer.class, Integer::valueOf);
		setMethodCallConversion(String.class, Long.class, Long::valueOf);
		setMethodCallConversion(String.class, Float.class, Float::valueOf);
		setMethodCallConversion(String.class, Double.class, Double::valueOf);
		setMethodCallConversion(String.class, UUID.class, UUID::fromString);

		setMethodCallConversion(UUID.class, String.class, Object::toString);
	}
	private static <T> List<T> toList(Object array){
		assert(array.getClass().isArray());
		int len = Array.getLength(array);
		List<T> list = new ArrayList<>(len);
		for(int i=0; i<len; i++){
			list.add((T)Array.get(array, i));
		}
		return list;
	}
	private static boolean[] toBooleanArray(List<Boolean> list) {
		boolean[] array = new boolean[list.size()];
		int len = list.size();
		for(int i=0; i<len; i++){
			array[i] = list.get(i);
		}
		return array;
	}
	private static short[] toShortArray(List<Short> list) { return toArray(list, l -> new short[l]); }
	private static int[] toIntArray(List<Integer> list) { return toArray(list, l -> new int[l]); }
	private static long[] toLongArray(List<Long> list) { return toArray(list, l -> new long[l]); }
	private static float[] toFloatArray(List<Float> list) { return toArray(list, l -> new float[l]); }
	private static double[] toDoubleArray(List<Double> list) { return toArray(list, l -> new double[l]); }
	private static <T,U> T toArray(List<U> list, Function<Integer,T> c) {
		int len = list.size();
		T array = c.apply(len);
		assert(array.getClass().isArray());
		for(int i=0; i<len; i++){
			Array.set(array, i, list.get(i));
		}
		return array;
	}

}
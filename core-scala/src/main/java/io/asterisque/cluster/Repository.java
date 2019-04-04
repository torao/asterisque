/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.cluster;

import io.asterisque.Asterisque;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Repository
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 単純な KVS の機能を提供します。
 *
 * @author Takami Torao
 */
public interface Repository {

  // ==============================================================================================
  // オンメモリリポジトリ
  // ==============================================================================================
  /**
   * オンメモリでデータを保持するリポジトリのインスタンスです。
   */
  public static final Repository OnMemory = new MemoryRepository();

  // ==============================================================================================
  // 新規 UUID の取得
  // ==============================================================================================
  /**
   * このリポジトリのスコープで新規の UUID を参照します。
   * {@link Asterisque#Zero} と同値の UUID は予約されています。
   */
  public UUID nextUUID();

  // ==============================================================================================
  // データの保存
  // ==============================================================================================
  /**
   * 指定されたバイナリデータを保存します。
   * @param id データの ID
   * @param binary バイナリデータ
   * @param expires データの有効期限 (現在時刻からのミリ秒)
   */
  public void store(Optional<Principal> principal, UUID id, byte[] binary, long expires);

  // ==============================================================================================
  // データの復元
  // ==============================================================================================
  /**
   * 指定された ID のデータを復元しリポジトリから削除します。
   * @param id データの ID
   * @return 復元されたデータ
   */
  public Optional<byte[]> loadAndDelete(Optional<Principal> principal, UUID id);

}

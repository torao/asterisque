package io.asterisque.wire

import javax.annotation.Nonnull

class RemoteException(@Nonnull message:String) extends Exception(message)

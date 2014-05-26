/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io

package object asterisque {

	case class ::(local:LocalNode, port:Int){

	}

	implicit class _RichLocalNode(local:LocalNode){
	}

}

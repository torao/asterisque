/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.conf;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ConfigurationException
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public class ConfigurationException extends RuntimeException {
	public ConfigurationException(){ }
	public ConfigurationException(String msg){ super(msg); }
	public ConfigurationException(Throwable ex){ super(ex); }
	public ConfigurationException(String msg, Throwable ex){ super(msg, ex); }
}

/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.logging.Level;
import java.util.regex.Matcher;

import sun.misc.Unsafe;

/**
 * Expose Reactor runtime properties and methods such as Unsafe access, Android environment
 * or internal logger.
 *
 * Original Reference :
 * <a href='https://github.com/netty/netty/blob/master/common/src/main/java/io/netty/util/internal/Reactor.java'>Netty</a>.
 */
public abstract class Reactor {

	public static final  boolean TRACE_CANCEL                    =
			Boolean.parseBoolean(System.getProperty("reactor.trace.cancel", "false"));

	public static boolean TRACE_OPERATOR_STACKTRACE =
			Boolean.parseBoolean(System.getProperty("reactor.trace.operatorStacktrace",
					"false"));

	public static final  boolean TRACE_NOCAPACITY                =
			Boolean.parseBoolean(System.getProperty("reactor.trace.nocapacity", "false"));
	/**
	 *
	 */
	public static final long     DEFAULT_TIMEOUT                 =
			Long.parseLong(System.getProperty("reactor.await.defaultTimeout", "30000"));
	/**
	 * Whether the RingBuffer*Processor can be graphed by wrapping the individual Sequence with the target downstream
	 */
	public static final  boolean TRACEABLE_RING_BUFFER_PROCESSOR =
			Boolean.parseBoolean(System.getProperty("reactor.ringbuffer.trace", "true"));
	/**
	 * Default number of processors available to the runtime on init (min 4)
	 * @see Runtime#availableProcessors()
	 */
	public static final int DEFAULT_POOL_SIZE =
			Math.max(Runtime.getRuntime().availableProcessors(), 4);

	private static final boolean HAS_UNSAFE = hasUnsafe0();

	private final static LoggerFactory LOGGER_FACTORY;

	static {
		LoggerFactory loggerFactory;
		String name = LoggerFactory.class.getName();
		try {
			loggerFactory = new Slf4JLoggerFactory();
			loggerFactory.getLogger(name).debug("Using Slf4j logging framework");
		}
		catch (Throwable t) {
			loggerFactory = new JdkLoggerFactory();
			loggerFactory.getLogger(name).debug("Using JDK logging framework");
		}
		LOGGER_FACTORY = loggerFactory;
	}

	@SuppressWarnings("unchecked")
	public static <U, W> AtomicReferenceFieldUpdater<U, W> newAtomicReferenceFieldUpdater(
			Class<U> tclass, String fieldName) {
		if (hasUnsafe()) {
			try {
				return UnsafeSupport.newAtomicReferenceFieldUpdater(tclass, fieldName);
			} catch (Throwable ignore) {
				// ignore
			}
		}
		return AtomicReferenceFieldUpdater.newUpdater(tclass, (Class<W>)Object.class, fieldName);
	}

	/**
	 * Return {@code true} if {@code sun.misc.Unsafe} was found on the classpath and can be used for acclerated
	 * direct memory access.
	 * @return true if unsafe is present
	 */
	public static boolean hasUnsafe() {
		return HAS_UNSAFE;
	}

	/**
	 * Return the {@code sun.misc.Unsafe} instance if found on the classpath and can be used for acclerated
	 * direct memory access.
	 *
	 * @param <T> the Unsafe type
	 * @return the Unsafe instance
	 */
	@SuppressWarnings("unchecked")
	public static <T> T getUnsafe() {
		return (T) UnsafeSupport.getUnsafe();
	}

	private static boolean isAndroid() {
		boolean android;
		try {
			Class.forName("android.app.Application", false, UnsafeSupport.getSystemClassLoader());
			android = true;
		} catch (Exception e) {
			// Failed to load the class uniquely available in Android.
			android = false;
		}

		return android;
	}

	private static boolean hasUnsafe0() {

		if (isAndroid()) {
			return false;
		}

		try {
			return UnsafeSupport.hasUnsafe();
		} catch (Throwable t) {
			// Probably failed to initialize Reactor0.
			return false;
		}
	}


	/**
	 * Borrowed from Netty project which itself borrows from JCTools and various other projects.
	 *
	 * @see <a href="https://github.com/netty/netty/blob/master/common/src/main/java/io/netty/util/internal/Reactor.java">Netty javadoc</a>
	 * operations which requires access to {@code sun.misc.*}.
	 */
	private enum UnsafeSupport {
	;

		private static final Unsafe UNSAFE;


		static {
			ByteBuffer direct = ByteBuffer.allocateDirect(1);
			Field addressField;
			try {
				addressField = Buffer.class.getDeclaredField("address");
				addressField.setAccessible(true);
				if (addressField.getLong(ByteBuffer.allocate(1)) != 0) {
					// A heap buffer must have 0 address.
					addressField = null;
				} else {
					if (addressField.getLong(direct) == 0) {
						// A direct buffer must have non-zero address.
						addressField = null;
					}
				}
			} catch (Throwable t) {
				// Failed to access the address field.
				addressField = null;
			}
			Unsafe unsafe;
			if (addressField != null) {
				try {
					Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
					unsafeField.setAccessible(true);
					unsafe = (Unsafe) unsafeField.get(null);

					// Ensure the unsafe supports all necessary methods to work around the mistake in the latest OpenJDK.
					// https://github.com/netty/netty/issues/1061
					// http://www.mail-archive.com/jdk6-dev@openjdk.java.net/msg00698.html
					try {
						if (unsafe != null) {
							unsafe.getClass().getDeclaredMethod(
									"copyMemory", Object.class, long.class, Object.class, long.class, long.class);
						}
					} catch (NoSuchMethodError | NoSuchMethodException t) {
						throw t;
					}
				} catch (Throwable cause) {
					// Unsafe.copyMemory(Object, long, Object, long, long) unavailable.
					unsafe = null;
				}
			} else {
				// If we cannot access the address of a direct buffer, there's no point of using unsafe.
				// Let's just pretend unsafe is unavailable for overall simplicity.
				unsafe = null;
			}

			UNSAFE = unsafe;
		}

		static boolean hasUnsafe() {
			return UNSAFE != null;
		}

		public static Unsafe getUnsafe(){
			return UNSAFE;
		}


		static <U, W> AtomicReferenceFieldUpdater<U, W> newAtomicReferenceFieldUpdater(
				Class<U> tclass, String fieldName) throws Exception {
			return new UnsafeAtomicReferenceFieldUpdater<>(tclass, fieldName);
		}

		static ClassLoader getSystemClassLoader() {
			if (System.getSecurityManager() == null) {
				return ClassLoader.getSystemClassLoader();
			} else {
				return AccessController.doPrivileged((PrivilegedAction<ClassLoader>) ClassLoader::getSystemClassLoader);
			}
		}

		UnsafeSupport() {
		}

		private static final class UnsafeAtomicReferenceFieldUpdater<U, M> extends AtomicReferenceFieldUpdater<U, M> {
			private final long offset;

			UnsafeAtomicReferenceFieldUpdater(Class<U> tClass, String fieldName) throws NoSuchFieldException {
				Field field = tClass.getDeclaredField(fieldName);
				if (!Modifier.isVolatile(field.getModifiers())) {
					throw new IllegalArgumentException("Must be volatile");
				}
				offset = UNSAFE.objectFieldOffset(field);
			}

			@Override
			public boolean compareAndSet(U obj, M expect, M update) {
				return UNSAFE.compareAndSwapObject(obj, offset, expect, update);
			}

			@Override
			public boolean weakCompareAndSet(U obj, M expect, M update) {
				return UNSAFE.compareAndSwapObject(obj, offset, expect, update);
			}

			@Override
			public void set(U obj, M newValue) {
				UNSAFE.putObjectVolatile(obj, offset, newValue);
			}

			@Override
			public void lazySet(U obj, M newValue) {
				UNSAFE.putOrderedObject(obj, offset, newValue);
			}

			@SuppressWarnings("unchecked")
			@Override
			public M get(U obj) {
				return (M) UNSAFE.getObjectVolatile(obj, offset);
			}
		}
	}

	/**
	 * Try getting an appropriate
	 * {@link Logger} whether SLF4J is not present on the classpath or fallback to {@link java.util.logging.Logger}.
	 *
	 * @param name the category or logger name to assign
	 *
	 * @return a new {@link Logger} instance
	 */
	public static Logger getLogger(String name) {
		return LOGGER_FACTORY.getLogger(name);
	}

	/**
	 * Try getting an appropriate
	 * {@link Logger} whether SLF4J is not present on the classpath or fallback to {@link java.util.logging.Logger}.
	 *
	 * @param cls the source {@link Class} to derive the name from.
	 *
	 * @return a new {@link Logger} instance
	 */
	public static Logger getLogger(Class<?> cls) {
		return LOGGER_FACTORY.getLogger(cls.getName());
	}

	/**
	 * When enabled, producer declaration stacks are recorded via an intercepting
	 * "assembly tracker" operator and added as Suppressed
	 * Exception if the source producer fails.
	 *
	 * @return a true if assembly tracking is enabled
	 */
	public static boolean isOperatorStacktraceEnabled() {
		return TRACE_OPERATOR_STACKTRACE;
	}

	/**
	 * Enable operator stack recorder. When a producer is declared, an "assembly
	 * tracker" operator is automatically added to capture declaration
	 * stack. Errors are observed and enriched with a Suppressed
	 * Exception detailing the original stack. Must be called before producers
	 * (e.g. Flux.map, Mono.fromCallable) are actually called to intercept the right
	 * stack information.
	 */
	public static void enableOperatorStacktrace() {
		TRACE_OPERATOR_STACKTRACE = true;
	}

	/**
	 * Disable operator stack recorder.
	 */
	public static void disableOperatorStacktrace() {
		TRACE_OPERATOR_STACKTRACE = false;
	}

	public interface Logger {

		int SUBSCRIBE     = 0b010000000;
		int ON_SUBSCRIBE  = 0b001000000;
		int ON_NEXT       = 0b000100000;
		int ON_ERROR      = 0b000010000;
		int ON_COMPLETE   = 0b000001000;
		int REQUEST       = 0b000000100;
		int CANCEL        = 0b000000010;
		int TERMINAL      = CANCEL | ON_COMPLETE | ON_ERROR;
		int ALL           = TERMINAL | REQUEST | ON_SUBSCRIBE | ON_NEXT | SUBSCRIBE;

		/**
		 * Return the name of this <code>Logger</code> instance.
		 * @return name of this logger instance
		 */
		String getName();

		/**
		 * Is the logger instance enabled for the TRACE level?
		 *
		 * @return True if this Logger is enabled for the TRACE level,
		 *         false otherwise.
		 */
		boolean isTraceEnabled();

		/**
		 * Log a message at the TRACE level.
		 *
		 * @param msg the message string to be logged
		 */
		void trace(String msg);

		/**
		 * Log a message at the TRACE level according to the specified format
		 * and arguments.
		 * <p/>
		 * <p>This form avoids superfluous string concatenation when the logger
		 * is disabled for the TRACE level. However, this variant incurs the hidden
		 * (and relatively small) cost of creating an <code>Object[]</code> before invoking the method,
		 * even if this logger is disabled for TRACE.</p>
		 *
		 * @param format    the format string
		 * @param arguments a list of 3 or more arguments
		 */
		void trace(String format, Object... arguments);

		/**
		 * Log an exception (throwable) at the TRACE level with an
		 * accompanying message.
		 *
		 * @param msg the message accompanying the exception
		 * @param t   the exception (throwable) to log
		 */
		void trace(String msg, Throwable t);

		/**
		 * Is the logger instance enabled for the DEBUG level?
		 *
		 * @return True if this Logger is enabled for the DEBUG level,
		 *         false otherwise.
		 */
		boolean isDebugEnabled();

		/**
		 * Log a message at the DEBUG level.
		 *
		 * @param msg the message string to be logged
		 */
		void debug(String msg);

		/**
		 * Log a message at the DEBUG level according to the specified format
		 * and arguments.
		 * <p/>
		 * <p>This form avoids superfluous string concatenation when the logger
		 * is disabled for the DEBUG level. However, this variant incurs the hidden
		 * (and relatively small) cost of creating an <code>Object[]</code> before invoking the method,
		 * even if this logger is disabled for DEBUG. </p>
		 *
		 * @param format    the format string
		 * @param arguments a list of 3 or more arguments
		 */
		void debug(String format, Object... arguments);

		/**
		 * Log an exception (throwable) at the DEBUG level with an
		 * accompanying message.
		 *
		 * @param msg the message accompanying the exception
		 * @param t   the exception (throwable) to log
		 */
		void debug(String msg, Throwable t);

		/**
		 * Is the logger instance enabled for the INFO level?
		 *
		 * @return True if this Logger is enabled for the INFO level,
		 *         false otherwise.
		 */
		boolean isInfoEnabled();

		/**
		 * Log a message at the INFO level.
		 *
		 * @param msg the message string to be logged
		 */
		void info(String msg);

		/**
		 * Log a message at the INFO level according to the specified format
		 * and arguments.
		 * <p/>
		 * <p>This form avoids superfluous string concatenation when the logger
		 * is disabled for the INFO level. However, this variant incurs the hidden
		 * (and relatively small) cost of creating an <code>Object[]</code> before invoking the method,
		 * even if this logger is disabled for INFO. </p>
		 *
		 * @param format    the format string
		 * @param arguments a list of 3 or more arguments
		 */
		void info(String format, Object... arguments);

		/**
		 * Log an exception (throwable) at the INFO level with an
		 * accompanying message.
		 *
		 * @param msg the message accompanying the exception
		 * @param t   the exception (throwable) to log
		 */
		void info(String msg, Throwable t);

		/**
		 * Is the logger instance enabled for the WARN level?
		 *
		 * @return True if this Logger is enabled for the WARN level,
		 *         false otherwise.
		 */
		boolean isWarnEnabled();

		/**
		 * Log a message at the WARN level.
		 *
		 * @param msg the message string to be logged
		 */
		void warn(String msg);

		/**
		 * Log a message at the WARN level according to the specified format
		 * and arguments.
		 * <p/>
		 * <p>This form avoids superfluous string concatenation when the logger
		 * is disabled for the WARN level. However, this variant incurs the hidden
		 * (and relatively small) cost of creating an <code>Object[]</code> before invoking the method,
		 * even if this logger is disabled for WARN. </p>
		 *
		 * @param format    the format string
		 * @param arguments a list of 3 or more arguments
		 */
		void warn(String format, Object... arguments);

		/**
		 * Log an exception (throwable) at the WARN level with an
		 * accompanying message.
		 *
		 * @param msg the message accompanying the exception
		 * @param t   the exception (throwable) to log
		 */
		void warn(String msg, Throwable t);

		/**
		 * Is the logger instance enabled for the ERROR level?
		 *
		 * @return True if this Logger is enabled for the ERROR level,
		 *         false otherwise.
		 */
		boolean isErrorEnabled();

		/**
		 * Log a message at the ERROR level.
		 *
		 * @param msg the message string to be logged
		 */
		void error(String msg);

		/**
		 * Log a message at the ERROR level according to the specified format
		 * and arguments.
		 * <p/>
		 * <p>This form avoids superfluous string concatenation when the logger
		 * is disabled for the ERROR level. However, this variant incurs the hidden
		 * (and relatively small) cost of creating an <code>Object[]</code> before invoking the method,
		 * even if this logger is disabled for ERROR. </p>
		 *
		 * @param format    the format string
		 * @param arguments a list of 3 or more arguments
		 */
		void error(String format, Object... arguments);

		/**
		 * Log an exception (throwable) at the ERROR level with an
		 * accompanying message.
		 *
		 * @param msg the message accompanying the exception
		 * @param t   the exception (throwable) to log
		 */
		void error(String msg, Throwable t);

	}

	private interface LoggerFactory {
		Logger getLogger(String name);
	}

	private static class Slf4JLoggerFactory implements LoggerFactory {

		@Override
		public Logger getLogger(String name) {
			return new Slf4JLogger(org.slf4j.LoggerFactory.getLogger(name));
		}
	}

	private static class Slf4JLogger implements Logger {

		private final org.slf4j.Logger logger;

		public Slf4JLogger(org.slf4j.Logger logger) {
			this.logger = logger;
		}

		@Override
		public String getName() {
			return logger.getName();
		}

		@Override
		public boolean isTraceEnabled() {
			return logger.isTraceEnabled();
		}

		@Override
		public void trace(String msg) {
			logger.trace(msg);
		}

		@Override
		public void trace(String format, Object... arguments) {
			logger.trace(format, arguments);
		}

		@Override
		public void trace(String msg, Throwable t) {
			logger.trace(msg, t);
		}

		@Override
		public boolean isDebugEnabled() {
			return logger.isDebugEnabled();
		}

		@Override
		public void debug(String msg) {
			logger.debug(msg);
		}

		@Override
		public void debug(String format, Object... arguments) {
			logger.debug(format, arguments);
		}

		@Override
		public void debug(String msg, Throwable t) {
			logger.debug(msg, t);
		}

		@Override
		public boolean isInfoEnabled() {
			return logger.isInfoEnabled();
		}

		@Override
		public void info(String msg) {
			logger.info(msg);
		}

		@Override
		public void info(String format, Object... arguments) {
			logger.info(format, arguments);
		}

		@Override
		public void info(String msg, Throwable t) {
			logger.info(msg, t);
		}

		@Override
		public boolean isWarnEnabled() {
			return logger.isWarnEnabled();
		}

		@Override
		public void warn(String msg) {
			logger.warn(msg);
		}

		@Override
		public void warn(String format, Object... arguments) {
			logger.warn(format, arguments);
		}

		@Override
		public void warn(String msg, Throwable t) {
			logger.warn(msg, t);
		}

		@Override
		public boolean isErrorEnabled() {
			return logger.isErrorEnabled();
		}

		@Override
		public void error(String msg) {
			logger.error(msg);
		}

		@Override
		public void error(String format, Object... arguments) {
			logger.error(format, arguments);
		}

		@Override
		public void error(String msg, Throwable t) {
			logger.error(msg, t);
		}
	}

	/**
	 * Wrapper over JDK logger
	 */
	private static class JdkLogger implements Logger {

		private final java.util.logging.Logger logger;

		public JdkLogger(java.util.logging.Logger logger) {
			this.logger = logger;
		}

		@Override
		public String getName() {
			return logger.getName();
		}

		@Override
		public boolean isTraceEnabled() {
			return logger.isLoggable(Level.FINEST);
		}

		@Override
		public void trace(String msg) {
			logger.log(Level.FINEST, msg);
		}

		@Override
		public void trace(String format, Object... arguments) {
			logger.log(Level.FINEST, format(format, arguments));
		}

		@Override
		public void trace(String msg, Throwable t) {
			logger.log(Level.FINEST, msg, t);
		}

		@Override
		public boolean isDebugEnabled() {
			return logger.isLoggable(Level.FINE);
		}

		@Override
		public void debug(String msg) {
			logger.log(Level.FINE, msg);
		}

		@Override
		public void debug(String format, Object... arguments) {
			logger.log(Level.FINE, format(format, arguments));
		}

		@Override
		public void debug(String msg, Throwable t) {
			logger.log(Level.FINE, msg, t);
		}

		@Override
		public boolean isInfoEnabled() {
			return logger.isLoggable(Level.INFO);
		}

		@Override
		public void info(String msg) {
			logger.log(Level.INFO, msg);
		}

		@Override
		public void info(String format, Object... arguments) {
			logger.log(Level.INFO, format(format, arguments));
		}

		@Override
		public void info(String msg, Throwable t) {
			logger.log(Level.INFO, msg, t);
		}

		@Override
		public boolean isWarnEnabled() {
			return logger.isLoggable(Level.WARNING);
		}

		@Override
		public void warn(String msg) {
			logger.log(Level.WARNING, msg);
		}

		@Override
		public void warn(String format, Object... arguments) {
			logger.log(Level.WARNING, format(format, arguments));
		}

		@Override
		public void warn(String msg, Throwable t) {
			logger.log(Level.WARNING, msg, t);
		}

		@Override
		public boolean isErrorEnabled() {
			return logger.isLoggable(Level.SEVERE);
		}

		@Override
		public void error(String msg) {
			logger.log(Level.SEVERE, msg);
		}

		@Override
		public void error(String format, Object... arguments) {
			logger.log(Level.SEVERE, format(format, arguments));
		}

		@Override
		public void error(String msg, Throwable t) {
			logger.log(Level.SEVERE, msg, t);
		}

		private String format(String from, Object... arguments){
			if(from != null) {
				String computed = from;
				if (arguments != null && arguments.length != 0) {
					for (Object argument : arguments) {
						computed = computed.replaceFirst("\\{\\}", Matcher.quoteReplacement(argument.toString()));
					}
				}
				return computed;
			}
			return null;
		}
	}

	private static class JdkLoggerFactory implements LoggerFactory {
		@Override
		public Logger getLogger(String name) {
			return new JdkLogger(java.util.logging.Logger.getLogger(name));
		}
	}

	Reactor(){}
}

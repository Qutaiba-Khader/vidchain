-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keepattributes Signature
-keep class org.apache.commons.compress.archivers.zip.** { *; }

-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

-dontwarn javax.script.AbstractScriptEngine
-dontwarn javax.script.Bindings
-dontwarn javax.script.Compilable
-dontwarn javax.script.CompiledScript
-dontwarn javax.script.Invocable
-dontwarn javax.script.ScriptContext
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptEngineFactory
-dontwarn javax.script.ScriptException
-dontwarn javax.script.SimpleBindings

-dontwarn jdk.dynalink.CallSiteDescriptor
-dontwarn jdk.dynalink.DynamicLinker
-dontwarn jdk.dynalink.DynamicLinkerFactory
-dontwarn jdk.dynalink.NamedOperation
-dontwarn jdk.dynalink.Namespace
-dontwarn jdk.dynalink.NamespaceOperation
-dontwarn jdk.dynalink.Operation
-dontwarn jdk.dynalink.RelinkableCallSite
-dontwarn jdk.dynalink.StandardNamespace
-dontwarn jdk.dynalink.StandardOperation
-dontwarn jdk.dynalink.linker.GuardedInvocation
-dontwarn jdk.dynalink.linker.GuardingDynamicLinker
-dontwarn jdk.dynalink.linker.LinkRequest
-dontwarn jdk.dynalink.linker.LinkerServices
-dontwarn jdk.dynalink.linker.TypeBasedGuardingDynamicLinker
-dontwarn jdk.dynalink.linker.support.CompositeTypeBasedGuardingDynamicLinker
-dontwarn jdk.dynalink.linker.support.Guards
-dontwarn jdk.dynalink.support.ChainedCallSite

-dontwarn com.thoughtworks.paranamer.AdaptiveParanamer
-dontwarn com.thoughtworks.paranamer.Paranamer

-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Graphics
-dontwarn java.awt.Image
-dontwarn java.awt.Point
-dontwarn java.awt.geom.Point2D$Double
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D$Double
-dontwarn java.awt.geom.Rectangle2D
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.json.spi.JsonProvider

-dontwarn sun.reflect.ReflectionFactory

-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}

-optimizationpasses 7
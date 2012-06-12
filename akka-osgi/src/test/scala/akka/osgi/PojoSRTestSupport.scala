package akka.osgi

import de.kalpatec.pojosr.framework.launch.{ BundleDescriptor, PojoServiceRegistryFactory, ClasspathScanner }

import scala.collection.JavaConversions.seqAsJavaList
import scala.collection.JavaConversions.collectionAsScalaIterable
import org.apache.commons.io.IOUtils.copy

import org.osgi.framework._
import java.net.URL

import java.util.jar.JarInputStream
import java.io.{ FileInputStream, FileOutputStream, File }
import java.util.{ Date, ServiceLoader, HashMap }
import org.scalatest.{ BeforeAndAfterAll, Suite }

/**
 * Trait that provides support for building akka-osgi tests using PojoSR
 */
trait PojoSRTestSupport extends Suite with BeforeAndAfterAll {

  val MAX_WAIT_TIME = 8000;
  val START_WAIT_TIME = 100;

  implicit def buildBundleDescriptor(builder: BundleDescriptorBuilder) = builder.build

  /**
   * All bundles being found on the test classpath are automatically installed and started in the PojoSR runtime.
   * Implement this to define the extra bundles that should be available for testing.
   */
  val testBundles: Seq[BundleDescriptor]

  lazy val context: BundleContext = {
    val config = new HashMap[String, AnyRef]();
    System.setProperty("org.osgi.framework.storage", "target/akka-osgi/" + System.currentTimeMillis)

    val bundles = new ClasspathScanner().scanForBundles()
    bundles.addAll(testBundles)
    config.put(PojoServiceRegistryFactory.BUNDLE_DESCRIPTORS, bundles);

    val loader: ServiceLoader[PojoServiceRegistryFactory] = ServiceLoader.load(classOf[PojoServiceRegistryFactory])

    val registry = loader.iterator.next.newPojoServiceRegistry(config)
    registry.getBundleContext
  }

  // Ensure bundles get stopped at the end of the test to release resources and stop threads
  override protected def afterAll() = context.getBundles.foreach(_.stop)

  /**
   * Convenience method to find a bundle by symbolic name
   */
  def bundleForName(name: String) = context.getBundles.find(_.getSymbolicName == name) match {
    case Some(bundle) ⇒ bundle
    case None         ⇒ fail("Unable to find bundle with symbolic name %s".format(name))
  }

  /**
   * Convenience method to find a service by interface.  If the service is not already available in the OSGi Service
   * Registry, this method will wait for a few seconds for the service to appear.
   */
  def serviceForType[T](implicit manifest: Manifest[T]): T = {
    val reference = awaitReference(manifest.erasure)
    context.getService(reference).asInstanceOf[T]
  }

  def awaitReference(serviceType: Class[_]): ServiceReference = awaitReference(serviceType, START_WAIT_TIME)

  def awaitReference(serviceType: Class[_], wait: Long): ServiceReference = {
    val option = Option(context.getServiceReference(serviceType.getName))
    option match {
      case Some(reference)                ⇒ reference;
      case None if (wait > MAX_WAIT_TIME) ⇒ fail("Gave up waiting for service of type %s".format(serviceType))
      case None ⇒ {
        Thread.sleep(wait);
        awaitReference(serviceType, wait * 2);
      }
    }
  }
}

object PojoSRTestSupport {

  /**
   * Convenience method to define additional test bundles
   */
  def bundle(name: String) = new BundleDescriptorBuilder(name)

}

/**
 * Helper class to make it easier to define test bundles
 */
class BundleDescriptorBuilder(name: String) {

  import org.ops4j.pax.tinybundles.core.TinyBundles

  val tinybundle = TinyBundles.bundle.set(Constants.BUNDLE_SYMBOLICNAME, name)

  def withBlueprintFile(name: String, contents: URL) =
    returnBuilder(tinybundle.add("OSGI-INF/blueprint/%s".format(name), contents))

  def withBlueprintFile(contents: URL): BundleDescriptorBuilder = withBlueprintFile(filename(contents), contents)

  def withActivator(activator: Class[_ <: BundleActivator]) =
    returnBuilder(tinybundle.set(Constants.BUNDLE_ACTIVATOR, activator.getName))

  def returnBuilder(block: ⇒ Unit) = {
    block
    this
  }

  def build = {
    val file: File = tinybundleToJarFile(name)

    new BundleDescriptor(
      getClass().getClassLoader(),
      new URL("jar:" + file.toURI().toString() + "!/"),
      extractHeaders(file));
  }

  def extractHeaders(file: File): HashMap[String, String] = {
    val headers = new HashMap[String, String]();

    val jis = new JarInputStream(new FileInputStream(file));
    try {
      for (entry ← jis.getManifest().getMainAttributes().entrySet()) {
        headers.put(entry.getKey().toString(), entry.getValue().toString());
      }
    } finally {
      jis.close()
    }

    headers
  }

  def tinybundleToJarFile(name: String): File = {
    val file = new File("target/%s-%tQ.jar".format(name, new Date()));
    val fos = new FileOutputStream(file);
    try {
      copy(tinybundle.build(), fos);
    } finally {
      fos.close();
    }
    file
  }

  private[this] def filename(url: URL) = url.getFile.split("/").last
}


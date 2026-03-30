package com.intellij.ide.plugins;

import com.intellij.platform.plugins.parser.impl.PluginDescriptorBuilder;
import com.intellij.platform.plugins.parser.impl.PluginDescriptorFromXmlStreamConsumer;
import com.intellij.platform.plugins.parser.impl.PluginDescriptorReaderContext;
import com.intellij.platform.plugins.parser.impl.XIncludeLoader;
import com.intellij.util.xml.dom.StaxFactory;
import org.codehaus.stax2.XMLStreamReader2;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridge PluginXmlPathResolver that implements the bridge {@link PathResolver} interface,
 * satisfying both the old 4-arg API (AA via kotlin-compiler.jar) and the new 3-arg API
 * (IJ 2025.3 PluginDescriptorLoader via app.jar).
 *
 * <p>Old-API methods delegate to the old {@code com.intellij.ide.plugins.XmlReader}
 * (available from the compat JAR extraction of kotlin-compiler.jar).
 *
 * <p>New-API methods use {@code PluginDescriptorFromXmlStreamConsumer} and the new
 * {@code com.intellij.platform.plugins.parser.impl.XmlReader} from app.jar.
 */
@SuppressWarnings({"rawtypes", "unchecked", "unused", "RedundantSuppression"})
public final class PluginXmlPathResolver implements PathResolver {

    /** Singleton used by PluginDescriptorLoader and AA's PluginStructureProvider. */
    public static final PathResolver DEFAULT_PATH_RESOLVER =
            new PluginXmlPathResolver(Collections.emptyList(), null);

    private final List<Path> pluginJarFiles;
    @SuppressWarnings("FieldCanBeLocal")
    private final Object zipFilePool; // Object — avoids old/new ZipFilePool type conflict

    /**
     * Constructor matching both old signature {@code (List<Path>, ZipFilePool)} and
     * new signature {@code (List<Path>, ZipEntryResolverPool)} via erasure to Object.
     */
    public PluginXmlPathResolver(List<? extends Path> pluginJarFiles, Object zipFilePool) {
        this.pluginJarFiles = new ArrayList<>(pluginJarFiles);
        this.zipFilePool = zipFilePool;
    }

    // ── Old API ─────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean loadXIncludeReference(
            RawPluginDescriptor descriptor,
            ReadModuleContext context,
            DataLoader dataLoader,
            String base,
            String relativePath) {
        InputStream stream;
        try {
            stream = dataLoader.load(relativePath, /* ignoreNotFound = */ true);
        } catch (Exception e) {
            return false;
        }
        if (stream == null) return false;
        try {
            com.intellij.ide.plugins.XmlReader.readModuleDescriptor(
                    stream, context, this, dataLoader, base, descriptor, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public RawPluginDescriptor resolvePath(
            ReadModuleContext readContext,
            DataLoader dataLoader,
            String relativePath,
            RawPluginDescriptor descriptor) {
        InputStream stream;
        try {
            stream = dataLoader.load(relativePath, /* ignoreNotFound = */ false);
        } catch (Exception e) {
            return null;
        }
        if (stream == null) return null;
        try {
            return com.intellij.ide.plugins.XmlReader.readModuleDescriptor(
                    stream, readContext, this, dataLoader, relativePath, descriptor, null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public RawPluginDescriptor resolveModuleFile(
            ReadModuleContext readContext,
            DataLoader dataLoader,
            String relativePath,
            RawPluginDescriptor descriptor) {
        return resolvePath(readContext, dataLoader, relativePath, descriptor);
    }

    // ── New API ─────────────────────────────────────────────────────────────────────────────

    @Override
    public XIncludeLoader.LoadedXIncludeReference loadXIncludeReference(
            DataLoader dataLoader,
            String relativePath) {
        try {
            InputStream stream = dataLoader.load(relativePath, /* ignoreNotFound = */ true);
            if (stream == null) return null;
            return new XIncludeLoader.LoadedXIncludeReference(stream, relativePath);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public PluginDescriptorBuilder resolvePath(
            PluginDescriptorReaderContext context,
            DataLoader dataLoader,
            String relativePath) {
        InputStream stream;
        try {
            stream = dataLoader.load(relativePath, /* ignoreNotFound = */ false);
        } catch (Exception e) {
            return null;
        }
        if (stream == null) return null;
        try {
            XIncludeLoader xil = PathResolverKt.toXIncludeLoader(this, dataLoader);
            PluginDescriptorFromXmlStreamConsumer consumer =
                    new PluginDescriptorFromXmlStreamConsumer(context, xil);
            XMLStreamReader2 reader = StaxFactory.createXmlStreamReader(stream);
            com.intellij.platform.plugins.parser.impl.XmlReader.readModuleDescriptor(
                    consumer, reader);
            return consumer.getBuilder();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public PluginDescriptorBuilder resolveModuleFile(
            PluginDescriptorReaderContext context,
            DataLoader dataLoader,
            String relativePath) {
        return resolvePath(context, dataLoader, relativePath);
    }
}

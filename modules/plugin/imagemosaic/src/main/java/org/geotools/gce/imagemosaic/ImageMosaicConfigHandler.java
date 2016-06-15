/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 * 
 *    (C) 2013 - 2016, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.gce.imagemosaic;

import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.SampleModel;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.imageio.spi.ImageReaderSpi;
import javax.media.jai.ImageLayout;
import javax.xml.bind.JAXBException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.coverage.grid.io.GranuleSource;
import org.geotools.coverage.grid.io.GranuleStore;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.StructuredGridCoverage2DReader;
import org.geotools.coverage.grid.io.footprint.MultiLevelROIProvider;
import org.geotools.data.DataSourceException;
import org.geotools.data.DataStoreFactorySpi;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.factory.Hints;
import org.geotools.factory.Hints.Key;
import org.geotools.feature.collection.AbstractFeatureVisitor;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.gce.imagemosaic.Utils.Prop;
import org.geotools.gce.imagemosaic.catalog.CatalogConfigurationBean;
import org.geotools.gce.imagemosaic.catalog.GranuleCatalog;
import org.geotools.gce.imagemosaic.catalog.GranuleCatalogFactory;
import org.geotools.gce.imagemosaic.catalog.MultiLevelROIProviderMosaicFactory;
import org.geotools.gce.imagemosaic.catalog.index.DomainType;
import org.geotools.gce.imagemosaic.catalog.index.DomainsType;
import org.geotools.gce.imagemosaic.catalog.index.Indexer;
import org.geotools.gce.imagemosaic.catalog.index.Indexer.Collectors;
import org.geotools.gce.imagemosaic.catalog.index.Indexer.Collectors.Collector;
import org.geotools.gce.imagemosaic.catalog.index.Indexer.Coverages;
import org.geotools.gce.imagemosaic.catalog.index.Indexer.Coverages.Coverage;
import org.geotools.gce.imagemosaic.catalog.index.IndexerUtils;
import org.geotools.gce.imagemosaic.catalog.index.ParametersType;
import org.geotools.gce.imagemosaic.catalog.index.ParametersType.Parameter;
import org.geotools.gce.imagemosaic.catalog.index.SchemaType;
import org.geotools.gce.imagemosaic.catalog.index.SchemasType;
import org.geotools.gce.imagemosaic.catalogbuilder.CatalogBuilderConfiguration;
import org.geotools.gce.imagemosaic.catalogbuilder.MosaicBeanBuilder;
import org.geotools.gce.imagemosaic.properties.DefaultPropertiesCollectorSPI;
import org.geotools.gce.imagemosaic.properties.PropertiesCollector;
import org.geotools.gce.imagemosaic.properties.PropertiesCollectorFinder;
import org.geotools.gce.imagemosaic.properties.PropertiesCollectorSPI;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.referencing.CRS;
import org.geotools.resources.coverage.CoverageUtilities;
import org.geotools.util.DefaultProgressListener;
import org.geotools.util.Utilities;
import org.opengis.feature.Feature;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.PrecisionModel;

/**
 * This class is in responsible for creating and managing the catalog and the configuration of the mosaic
 * 
 * @author Carlo Cancellieri - GeoSolutions SAS
 *
 */
public class ImageMosaicConfigHandler {

    /** Default Logger * */
    final static Logger LOGGER = org.geotools.util.logging.Logging
            .getLogger(ImageMosaicConfigHandler.class);

    /* Used to check if we can use memory mapped buffers safely. In case the OS cannot be detected, we act as if it was Windows and
         * do not use memory mapped buffers */
    private final static Boolean USE_MEMORY_MAPPED_BUFFERS = !System.getProperty("os.name",
            "Windows").contains("Windows");

    private final static PrecisionModel PRECISION_MODEL = new PrecisionModel(PrecisionModel.FLOATING);

    private final static GeometryFactory GEOM_FACTORY = new GeometryFactory(PRECISION_MODEL);

    private List<PropertiesCollector> propertiesCollectors = null;

    private Map<String, MosaicConfigurationBean> configurations = new HashMap<>();

    /**
     * Proper way to stop a thread is not by calling Thread.stop() but by using a shared variable that can be checked in order to notify a terminating
     * condition.
     */
    private volatile boolean stop = false;

    protected GranuleCatalog catalog;

    private CatalogBuilderConfiguration runConfiguration;

    private ImageReaderSpi cachedReaderSPI;

    private ReferencedEnvelope imposedBBox;

    private ImageMosaicReader parentReader;

    private File indexerFile;

    private File parent;

    private ImageMosaicEventHandlers eventHandler;

    private boolean useExistingSchema;

    /**
     * Default constructor
     *
     * @throws IllegalArgumentException
     */
    ImageMosaicConfigHandler(final CatalogBuilderConfiguration configuration,
            final ImageMosaicEventHandlers eventHandler) {
        Utilities.ensureNonNull("runConfiguration", configuration);

        Utilities.ensureNonNull("eventHandler", eventHandler);
        this.eventHandler = eventHandler;

        Indexer defaultIndexer = configuration.getIndexer();
        ParametersType params = null;
        String rootMosaicDir = null;
        if (defaultIndexer != null) {
            params = defaultIndexer.getParameters();
            rootMosaicDir = IndexerUtils.getParam(params, Prop.ROOT_MOSAIC_DIR);
            IndexerUtils.getParameterAsBoolean(Prop.USE_EXISTING_SCHEMA, defaultIndexer);
        }

        Utilities.ensureNonNull("root location", rootMosaicDir);

        // look for and indexer.properties file
        parent = new File(rootMosaicDir);
        indexerFile = new File(parent, Utils.INDEXER_XML);
        Indexer indexer = null;

        Hints hints = configuration.getHints();
        String ancillaryFile = null;
        String datastoreFile = null;
        if (Utils.checkFileReadable(indexerFile)) {
            try {
                indexer = Utils.unmarshal(indexerFile);
                if (indexer != null) {
                    copyDefaultParams(params, indexer);
                }
            } catch (JAXBException e) {
                LOGGER.log(Level.WARNING, e.getMessage(), e);
            }
        } else {
            // Backward compatible with old indexing
            indexerFile = new File(parent, Utils.INDEXER_PROPERTIES);
            if (Utils.checkFileReadable(indexerFile)) {
                // load it and parse it
                final Properties props = CoverageUtilities.loadPropertiesFromURL(DataUtilities
                        .fileToURL(indexerFile));
                indexer = createIndexer(props, params);
            }
        }
        if (indexer != null) {
            // Overwrite default indexer only when indexer is available
            configuration.setIndexer(indexer);
            String auxiliaryFileParam = IndexerUtils.getParameter(Utils.Prop.AUXILIARY_FILE, indexer);
            if (auxiliaryFileParam != null) {
                ancillaryFile = auxiliaryFileParam;
            }
            String datastoreFileParam = IndexerUtils.getParameter(Utils.Prop.AUXILIARY_DATASTORE_FILE, indexer);
            if (datastoreFileParam != null) {
                datastoreFile = datastoreFileParam;
            }
            if (datastoreFileParam != null || auxiliaryFileParam != null) {
                setReader(hints, false);
            }
            if (IndexerUtils.getParameterAsBoolean(Utils.Prop.USE_EXISTING_SCHEMA, indexer)) {
                this.useExistingSchema = true;
            }
        }

        updateConfigurationHints(configuration, hints, ancillaryFile, datastoreFile, 
                IndexerUtils.getParam(params, Prop.ROOT_MOSAIC_DIR));

        // check config
        configuration.check();

        this.runConfiguration = new CatalogBuilderConfiguration(configuration);
    }

    /**
     * Create or load a GranuleCatalog on top of the provided configuration
     * @param runConfiguration configuration to be used
     * @param create if true create a new catalog, otherwise it is loaded
     * @return a new GranuleCatalog built from the configuration
     * @throws IOException
     */
    private GranuleCatalog createCatalog(CatalogBuilderConfiguration runConfiguration, boolean create) throws IOException {
        //
        // create the index
        //
        // do we have a datastore.properties file?
        final File parent = new File(runConfiguration.getParameter(Prop.ROOT_MOSAIC_DIR));
        GranuleCatalog catalog;

        // Consider checking that from the indexer if any
        final File datastoreProperties = new File(parent, "datastore.properties");
        // GranuleCatalog catalog = null;
        if (Utils.checkFileReadable(datastoreProperties)) {
            // read the properties file
            Properties properties = createGranuleCatalogProperties(datastoreProperties);
            // pass the typename from the indexer, if one is available
            String indexerTypeName = runConfiguration.getParameter(Prop.TYPENAME);
            if(indexerTypeName != null && properties.getProperty(Prop.TYPENAME) == null) {
                properties.put(Prop.TYPENAME, indexerTypeName);
            }
            catalog = createGranuleCatalogFromDatastore(parent, properties, create,
                    Boolean.parseBoolean(runConfiguration.getParameter(Prop.WRAP_STORE)),
                    runConfiguration.getHints());
        } else {

            // we do not have a datastore properties file therefore we continue with a shapefile datastore
            final URL file = new File(parent, runConfiguration.getParameter(Prop.INDEX_NAME) + ".shp").toURI().toURL();
            final Properties params = new Properties();
            params.put(ShapefileDataStoreFactory.URLP.key, file);
            if (file.getProtocol().equalsIgnoreCase("file")) {
                params.put(ShapefileDataStoreFactory.CREATE_SPATIAL_INDEX.key, Boolean.TRUE);
            }
            params.put(ShapefileDataStoreFactory.MEMORY_MAPPED.key, USE_MEMORY_MAPPED_BUFFERS);
            params.put(ShapefileDataStoreFactory.DBFTIMEZONE.key, TimeZone.getTimeZone("UTC"));
            params.put(Prop.LOCATION_ATTRIBUTE, runConfiguration.getParameter(Prop.LOCATION_ATTRIBUTE));
            catalog = GranuleCatalogFactory
                    .createGranuleCatalog(params, false, create, Utils.SHAPE_SPI,runConfiguration.getHints());
            MultiLevelROIProvider roi = MultiLevelROIProviderMosaicFactory.createFootprintProvider(parent);
            catalog.setMultiScaleROIProvider(roi);
        }

        return catalog;
    }

    static Properties createGranuleCatalogProperties(File datastoreProperties) throws IOException {
        Properties properties = CoverageUtilities.loadPropertiesFromURL(DataUtilities.fileToURL(datastoreProperties));
        if (properties == null) {
            throw new IOException("Unable to load properties from:" + datastoreProperties.getAbsolutePath());
        }
        return properties;
    }

    static GranuleCatalog createGranuleCatalogFromDatastore(File parent, File datastoreProperties,
            boolean create, Hints hints) throws IOException {
        return createGranuleCatalogFromDatastore(parent, datastoreProperties, create, false, hints);
    }

    /**
     * Create a granule catalog from a datastore properties file
     */
    private static GranuleCatalog createGranuleCatalogFromDatastore(File parent,
            File datastoreProperties, boolean create, boolean wraps, Hints hints) throws IOException {
        Utilities.ensureNonNull("datastoreProperties", datastoreProperties);
        Properties properties = createGranuleCatalogProperties(datastoreProperties);
        return crieateGranuleCatalogFromDatastore(parent, properties, create, wraps, hints);
    }

    private static GranuleCatalog createGranuleCatalogFromDatastore(File parent,
            Properties properties, boolean create, boolean wraps, Hints hints) throws IOException {
        GranuleCatalog catalog = null;
        // SPI
        final String SPIClass = properties.getProperty("SPI");
        try {
            // create a datastore as instructed
            final DataStoreFactorySpi spi = (DataStoreFactorySpi) Class.forName(SPIClass).newInstance();

            // set ParentLocation parameter since for embedded database like H2 we must change the database
            // to incorporate the path where to write the db
            properties.put("ParentLocation", DataUtilities.fileToURL(parent).toExternalForm());
            if (wraps) {
                properties.put(Prop.WRAP_STORE, wraps);
            }

            catalog = GranuleCatalogFactory.createGranuleCatalog(properties, false, create, spi,hints);
            MultiLevelROIProvider rois = MultiLevelROIProviderMosaicFactory.createFootprintProvider(parent);
            catalog.setMultiScaleROIProvider(rois);
        } catch (Exception e) {
            final IOException ioe = new IOException();
            throw (IOException) ioe.initCause(e);
        }
        return catalog;
    }

    /**
     * Create a {@link SimpleFeatureType} from the specified configuration.
     * @param configurationBean
     * @param actualCRS CRS of the mosaic
     * @return the schema for the mosaic
     */
    private SimpleFeatureType createSchema(CatalogBuilderConfiguration runConfiguration, String name,
            CoordinateReferenceSystem actualCRS) {
        SimpleFeatureType indexSchema = null;
        SchemaType schema = null;
        String schemaAttributes = null;
        Indexer indexer = runConfiguration.getIndexer();
        if (indexer != null) {
            SchemasType schemas = indexer.getSchemas();
            Coverage coverage = IndexerUtils.getCoverage(indexer, name);
            if (coverage != null) {
                schema = IndexerUtils.getSchema(indexer, coverage);
            }
            if (schema != null) {
                schemaAttributes = schema.getAttributes();
            } else if (schemas != null) {
                List<SchemaType> schemaList = schemas.getSchema();
                // CHECK THAT
                if (!schemaList.isEmpty()) {
                    schemaAttributes = schemaList.get(0).getAttributes();
                }
            }
        }
        if (schemaAttributes == null) {
            schemaAttributes = runConfiguration.getSchema(name);
        }
        if (schemaAttributes != null) {
            schemaAttributes = schemaAttributes.trim();
            // get the schema
            try {
                indexSchema = DataUtilities.createType(name, schemaAttributes);
                // override the crs in case the provided one was wrong or absent
                indexSchema = DataUtilities.createSubType(indexSchema,
                        DataUtilities.attributeNames(indexSchema), actualCRS);
                if (actualCRS != null) {
                    Set<ReferenceIdentifier> identifiers = actualCRS.getIdentifiers();
                    if (identifiers == null || identifiers.isEmpty()) {
                        Integer code = CRS.lookupEpsgCode(actualCRS, true);
                        int nativeSrid = code == null ? 0 : code;
                        GeometryDescriptor geometryDescriptor = indexSchema.getGeometryDescriptor();
                        if (geometryDescriptor != null) {
                            Map<Object, Object> userData = geometryDescriptor.getUserData();
                            userData.put(JDBCDataStore.JDBC_NATIVE_SRID, nativeSrid);
                        }
                    }
                }

            } catch (Throwable e) {
                LOGGER.log(Level.FINE, e.getLocalizedMessage(), e);
                indexSchema = null;
            }
        }

        if (indexSchema == null) {
            // Proceed with default Schema
            final SimpleFeatureTypeBuilder featureBuilder = new SimpleFeatureTypeBuilder();
            featureBuilder.setName(runConfiguration.getParameter(Prop.INDEX_NAME));
            featureBuilder.setNamespaceURI("http://www.geo-solutions.it/");
            featureBuilder.add(runConfiguration.getParameter(Prop.LOCATION_ATTRIBUTE).trim(), String.class);
            featureBuilder.add("the_geom", Polygon.class, actualCRS);
            featureBuilder.setDefaultGeometry("the_geom");
            String timeAttribute = runConfiguration.getTimeAttribute();
            addAttributes(timeAttribute, featureBuilder, Date.class);
            indexSchema = featureBuilder.buildFeatureType();
        }
        return indexSchema;
    }

    /**
     * Add splitted attributes to the featureBuilder
     *
     * @param attribute
     * @param featureBuilder
     * @param classType
     * TODO: Remove that once reworking on the dimension stuff
     */
    private static void addAttributes(String attribute, SimpleFeatureTypeBuilder featureBuilder,
            Class classType) {
        if (attribute != null) {
            if (!attribute.contains(Utils.RANGE_SPLITTER_CHAR)) {
                featureBuilder.add(attribute, classType);
            } else {
                String[] ranges = attribute.split(Utils.RANGE_SPLITTER_CHAR);
                if (ranges.length != 2) {
                    throw new IllegalArgumentException(
                            "All ranges attribute need to be composed of a maximum of 2 elements:\n"
                                    + "As an instance (min;max) or (low;high) or (begin;end) , ...");
                } else {
                    featureBuilder.add(ranges[0], classType);
                    featureBuilder.add(ranges[1], classType);
                }
            }
        }

    }

    /**
     * Get a {@link GranuleSource} related to a specific coverageName from an inputReader
     * and put the related granules into a {@link GranuleStore} related to the same coverageName
     * of the mosaicReader.
     *
     * @param coverageName the name of the coverage to be managed
     * @param fileBeingProcessed the reference input file
     * @param inputReader the reader source of granules
     * @param mosaicReader the reader where to store source granules
     * @param configuration the configuration
     * @param envelope envelope of the granule being added
     * @param transaction transaction in progress
     * @param propertiesCollectors list of properties collectors to use
     * @throws IOException
     */
    private void updateCatalog(
            final String coverageName,
            final File fileBeingProcessed,
            final GridCoverage2DReader inputReader,
            final ImageMosaicReader mosaicReader,
            final CatalogBuilderConfiguration configuration,
            final GeneralEnvelope envelope,
            final DefaultTransaction transaction,
            final List<PropertiesCollector> propertiesCollectors) throws IOException {

        // Retrieving the store and the destination schema
        final GranuleStore store = (GranuleStore) mosaicReader.getGranules(coverageName, false);
        if (store == null) {
            throw new IllegalArgumentException("No valid granule store has been found for: " + coverageName);
        }
        final SimpleFeatureType indexSchema = store.getSchema();
        final SimpleFeature feature = new ShapefileCompatibleFeature(DataUtilities.template(indexSchema));
        store.setTransaction(transaction);

        final ListFeatureCollection collection = new ListFeatureCollection(indexSchema);
        final String fileLocation = prepareLocation(configuration, fileBeingProcessed);
        final String locationAttribute = configuration.getParameter(Prop.LOCATION_ATTRIBUTE);

        // getting input granules
        if (inputReader instanceof StructuredGridCoverage2DReader) {

            //
            // Case A: input reader is a StructuredGridCoverage2DReader. We can get granules from a source
            //
            // Getting granule source and its input granules
            final GranuleSource source = ((StructuredGridCoverage2DReader) inputReader).getGranules(coverageName, true);
            final SimpleFeatureCollection originCollection = source.getGranules(null);
            final DefaultProgressListener listener = new DefaultProgressListener();

            // Getting attributes structure to be filled
            final Collection<Property> destProps = feature.getProperties();
            final Set<Name> destAttributes = new HashSet<>();
            for (Property prop: destProps) {
                destAttributes.add(prop.getName());
            }

            // Collecting granules
            originCollection.accepts( new AbstractFeatureVisitor(){
                public void visit( Feature feature ) {
                    if(feature instanceof SimpleFeature)
                    {
                            // get the feature
                            final SimpleFeature sourceFeature = (SimpleFeature) feature;
                            final SimpleFeature destFeature = DataUtilities.template(indexSchema);
                            Collection<Property> props = sourceFeature.getProperties();
                            Name propName = null;
                            Object propValue = null;

                            // Assigning value to dest feature for matching attributes
                            for (Property prop: props) {
                                propName = prop.getName();
                                propValue = prop.getValue();

                                // Matching attributes are set
                                if (destAttributes.contains(propName)) {
                                    destFeature.setAttribute(propName, propValue);
                                }
                            }

                            // Set location
                            destFeature.setAttribute(locationAttribute, fileLocation);

                            // delegate remaining attributes set to properties collector
                            updateAttributesFromCollectors(destFeature, fileBeingProcessed, inputReader, propertiesCollectors);
                            collection.add(destFeature);

                            // check if something bad occurred
                            if(listener.isCanceled()||listener.hasExceptions()){
                                if(listener.hasExceptions())
                                    throw new RuntimeException(listener.getExceptions().peek());
                                else
                                    throw new IllegalStateException("Feature visitor has been canceled");
                            }
                    }
                }
            }, listener);
        } else {
            //
            // Case B: old style reader, proceed with classic way, using properties collectors
            //
            feature.setAttribute(indexSchema.getGeometryDescriptor().getLocalName(),
                    GEOM_FACTORY.toGeometry(new ReferencedEnvelope(envelope)));
            feature.setAttribute(locationAttribute, fileLocation);

            updateAttributesFromCollectors(feature, fileBeingProcessed, inputReader, propertiesCollectors);
            collection.add(feature);
        }

        // drop all the granules associated to the same
        Filter filter = Utils.FF.equal(Utils.FF.property(locationAttribute), Utils.FF.literal(fileLocation),
                !isCaseSensitiveFileSystem(fileBeingProcessed));
        store.removeGranules(filter);

        // Add the granules collection to the store
        store.addGranules(collection);
    }

    /**
     * Checks if the file system is case sensitive or not using File.exists (the only method
     * that also works on OSX too according to
     * http://stackoverflow.com/questions/1288102/how-do-i-detect-whether-the-file-system-is-case-sensitive )
     * @param fileBeingProcessed
     * @return
     */
    private static boolean isCaseSensitiveFileSystem(File fileBeingProcessed) {
        File loCase = new File(fileBeingProcessed.getParentFile(), fileBeingProcessed.getName().toLowerCase());
        File upCase = new File(fileBeingProcessed.getParentFile(), fileBeingProcessed.getName().toUpperCase());
        return loCase.exists() && upCase.exists();
    }

    /**
     * Update feature attributes through properties collector
     * @param feature
     * @param fileBeingProcessed
     * @param inputReader
     * @param propertiesCollectors
     */
    private static void updateAttributesFromCollectors(
            final SimpleFeature feature,
            final File fileBeingProcessed,
            final GridCoverage2DReader inputReader,
            final List<PropertiesCollector> propertiesCollectors) {
        // collect and dump properties
        if (propertiesCollectors != null && propertiesCollectors.size() > 0)
            for (PropertiesCollector pc : propertiesCollectors) {
                pc.collect(fileBeingProcessed).collect(inputReader)
                        .setProperties(feature);
                pc.reset();
            }

    }

    /**
     * Prepare the location on top of the configuration and file to be processed.
     * @param runConfiguration
     * @param fileBeingProcessed
     * @return
     * @throws IOException
     */
    private static String prepareLocation(CatalogBuilderConfiguration runConfiguration, final File fileBeingProcessed) throws IOException {
        // absolute
        if (Boolean.valueOf(runConfiguration.getParameter(Prop.ABSOLUTE_PATH))) {
            return fileBeingProcessed.getAbsolutePath();
        }

        // relative
        String targetPath = fileBeingProcessed.getCanonicalPath();
        String basePath = runConfiguration.getParameter(Prop.ROOT_MOSAIC_DIR);
        String relative = getRelativePath(targetPath, basePath, File.separator); //TODO: Remove this replace after fixing the quote escaping
        return relative;
    }

    /**
     * Get the relative path from one file to another, specifying the directory separator.
     * If one of the provided resources does not exist, it is assumed to be a file unless it ends with '/' or
     * '\'.
     *
     * @param targetPath targetPath is calculated to this file
     * @param basePath basePath is calculated from this file
     * @param pathSeparator directory separator. The platform default is not assumed so that
     *        we can test Unix behaviour when running on Windows (for example)
     * @return
     */
    private static String getRelativePath(String targetPath, String basePath, String pathSeparator) {

        // Normalize the paths
        String normalizedTargetPath = FilenameUtils.normalizeNoEndSeparator(targetPath);
        String normalizedBasePath = FilenameUtils.normalizeNoEndSeparator(basePath);

        // Undo the changes to the separators made by normalization
        if (pathSeparator.equals("/")) {
            normalizedTargetPath = FilenameUtils.separatorsToUnix(normalizedTargetPath);
            normalizedBasePath = FilenameUtils.separatorsToUnix(normalizedBasePath);

        } else if (pathSeparator.equals("\\")) {
            normalizedTargetPath = FilenameUtils.separatorsToWindows(normalizedTargetPath);
            normalizedBasePath = FilenameUtils.separatorsToWindows(normalizedBasePath);

        } else {
            throw new IllegalArgumentException("Unrecognised dir separator '" + pathSeparator + "'");
        }

        String[] base = normalizedBasePath.split(Pattern.quote(pathSeparator));
        String[] target = normalizedTargetPath.split(Pattern.quote(pathSeparator));

        // First get all the common elements. Store them as a string,
        // and also count how many of them there are.
        StringBuilder common = new StringBuilder();

        int commonIndex = 0;
        while (commonIndex < target.length && commonIndex < base.length
                && target[commonIndex].equals(base[commonIndex])) {
            common.append(target[commonIndex] + pathSeparator);
            commonIndex++;
        }

        if (commonIndex == 0) {
            // No single common path element. This most
            // likely indicates differing drive letters, like C: and D:.
            // These paths cannot be relativized.
            throw new RuntimeException("No common path element found for '" + normalizedTargetPath
                    + "' and '" + normalizedBasePath + "'");
        }

        // The number of directories we have to backtrack depends on whether the base is a file or a dir
        // For example, the relative path from
        //
        // /foo/bar/baz/gg/ff to /foo/bar/baz
        //
        // ".." if ff is a file
        // "../.." if ff is a directory
        //
        // The following is a heuristic to figure out if the base refers to a file or dir. It's not perfect, because
        // the resource referred to by this path may not actually exist, but it's the best I can do
        boolean baseIsFile = true;

        File baseResource = new File(normalizedBasePath);

        if (baseResource.exists()) {
            baseIsFile = baseResource.isFile();

        } else if (basePath.endsWith(pathSeparator)) {
            baseIsFile = false;
        }

        StringBuilder relative = new StringBuilder();

        if (base.length != commonIndex) {
            int numDirsUp = baseIsFile ? base.length - commonIndex - 1 : base.length - commonIndex;

            for (int i = 0; i < numDirsUp; i++) {
                relative.append(".." + pathSeparator);
            }
        }
        relative.append(normalizedTargetPath.substring(common.length()));
        return relative.toString();
    }

    /**
     * Make sure a proper type name is specified in the catalogBean, it will be used to
     * create the {@link GranuleCatalog}
     *
     * @param sourceURL
     * @param configuration
     * @throws IOException
     */
    private static void checkTypeName(URL sourceURL, MosaicConfigurationBean configuration) throws IOException {
        CatalogConfigurationBean catalogBean = configuration.getCatalogConfigurationBean();
        if (catalogBean.getTypeName() == null) {
            if (sourceURL.getPath().endsWith("shp")) {
                // In case we didn't find a typeName and we are dealing with a shape index,
                // we set the typeName as the shape name
                final File file = DataUtilities.urlToFile(sourceURL);
                catalogBean.setTypeName(FilenameUtils.getBaseName(file.getCanonicalPath()));
            } else {
                // use the default "mosaic" name
                catalogBean.setTypeName("mosaic");
            }
        }
    }

    /**
     * Create a {@link GranuleCatalog} on top of the provided Configuration
     * @param sourceURL
     * @param configuration
     * @param hints
     * @return
     * @throws IOException
     */
    static GranuleCatalog createCatalog(final URL sourceURL, final MosaicConfigurationBean configuration, Hints hints) throws IOException {
        CatalogConfigurationBean catalogBean = configuration.getCatalogConfigurationBean();

        // Check the typeName
        checkTypeName(sourceURL, configuration);
        if (hints != null && hints.containsKey(Hints.MOSAIC_LOCATION_ATTRIBUTE)) {
            final String hintLocation = (String) hints
                    .get(Hints.MOSAIC_LOCATION_ATTRIBUTE);
            if (!catalogBean.getLocationAttribute().equalsIgnoreCase(hintLocation)) {
                throw new DataSourceException("wrong location attribute");
            }
        }
        // Create the catalog
        GranuleCatalog catalog = GranuleCatalogFactory.createGranuleCatalog(sourceURL, catalogBean, null,hints);
        File parent = DataUtilities.urlToFile(sourceURL).getParentFile();
        MultiLevelROIProvider rois = MultiLevelROIProviderMosaicFactory.createFootprintProvider(parent);
        catalog.setMultiScaleROIProvider(rois);

        return catalog;
    }

    private void setReader(Hints hints, final boolean updateHints) {
        if (hints != null && hints.containsKey(Utils.MOSAIC_READER)) {
            Object reader = hints.get(Utils.MOSAIC_READER);
            if (reader instanceof ImageMosaicReader) {
                if (getParentReader() == null) {
                    setParentReader((ImageMosaicReader) reader);
                }
                if (updateHints) {
                    Hints readerHints = getParentReader().getHints();
                    readerHints.add(hints);
                }
            }
        }
    }

    private void updateConfigurationHints(final CatalogBuilderConfiguration configuration,
            Hints hints, final String ancillaryFile, final String datastoreFile, final String rootMosaicDir) {
        final boolean isAbsolutePath = Boolean.parseBoolean(configuration.getParameter(Prop.ABSOLUTE_PATH));
        hints = updateHints(ancillaryFile, isAbsolutePath, 
                rootMosaicDir, configuration, hints, Utils.AUXILIARY_FILES_PATH);
        hints = updateHints(datastoreFile, isAbsolutePath, 
                rootMosaicDir, configuration, hints, Utils.AUXILIARY_DATASTORE_PATH);
        setReader(hints, true);
    }

    private Hints updateHints(String filePath, boolean isAbsolutePath, 
            String rootMosaicDir, CatalogBuilderConfiguration configuration, 
            Hints hints, Key key) {
        String updatedFilePath = null;
        if (filePath != null) {
            if (isAbsolutePath && !filePath.startsWith(rootMosaicDir)) {
                updatedFilePath = rootMosaicDir + File.separatorChar + filePath;
            } else {
                updatedFilePath = filePath;
            }

            if (hints != null) {
                hints.put(key, updatedFilePath);
            } else {
                hints = new Hints(key, updatedFilePath);
                configuration.setHints(hints);
            }
            if (!isAbsolutePath) {
                hints.put(Utils.PARENT_DIR, rootMosaicDir);
            }
        }
        return hints;
    }

    /**
     * Setup default params to the indexer.
     * 
     * @param params
     * @param indexer
     */
    private void copyDefaultParams(ParametersType params, Indexer indexer) {
        if (params != null) {
            List<Parameter> defaultParamList = params.getParameter();
            if (defaultParamList != null && !defaultParamList.isEmpty()) {
                ParametersType parameters = indexer.getParameters();
                if (parameters == null) {
                    parameters = Utils.OBJECT_FACTORY.createParametersType();
                    indexer.setParameters(parameters);
                }
                List<Parameter> parameterList = parameters.getParameter();
                for (Parameter defaultParameter : defaultParamList) {
                    final String defaultParameterName = defaultParameter.getName();
                    if (IndexerUtils.getParameter(defaultParameterName, indexer) == null) {
                        IndexerUtils.setParam(parameterList, defaultParameterName,
                                defaultParameter.getValue());
                    }
                }
            }
        }
    }

    /**
     * Perform proper clean up.
     * 
     * <p>
     * Make sure to call this method when you are not running the {@link ImageMosaicConfigHandler} or bad things can happen. If it is running, please
     * stop it first.
     */
    public void reset() {
        eventHandler.removeAllProcessingEventListeners();
        // clear stop
        stop = false;

        // fileIndex = 0;
        runConfiguration = null;

    }

    public boolean getStop() {
        return stop;
    }

    public void stop() {
        stop = true;
    }

    void indexingPreamble() throws IOException {

        this.catalog = buildCatalog();

        //
        // IMPOSED ENVELOPE
        //
        String bbox = runConfiguration.getParameter(Prop.ENVELOPE2D);
        try {
            this.imposedBBox = Utils.parseEnvelope(bbox);
        } catch (Exception e) {
            this.imposedBBox = null;
            if (LOGGER.isLoggable(Level.WARNING))
                LOGGER.log(Level.WARNING, "Unable to parse imposed bbox", e);
        }

        //
        // load property collectors
        //
        loadPropertyCollectors();
    }

    protected GranuleCatalog buildCatalog() throws IOException {
        GranuleCatalog catalog = createCatalog(runConfiguration, !useExistingSchema);
        getParentReader().granuleCatalog = catalog;
        return catalog;
    }

    /**
     * Load properties collectors from the configuration
     */
    private void loadPropertyCollectors() {
        // load property collectors
        Indexer indexer = runConfiguration.getIndexer();
        Collectors collectors = indexer.getCollectors();
        if (collectors == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("No properties collector have been found");
            }
            return;
        }
        List<Collector> collectorList = collectors.getCollector();

        // load the SPI set
        final Set<PropertiesCollectorSPI> pcSPIs = PropertiesCollectorFinder
                .getPropertiesCollectorSPI();

        // parse the string
        final List<PropertiesCollector> pcs = new ArrayList<PropertiesCollector>();
        for (Collector collector : collectorList) {
            PropertiesCollectorSPI selectedSPI = null;
            final String spiName = collector.getSpi();
            for (PropertiesCollectorSPI spi : pcSPIs) {
                if (spi.isAvailable() && spi.getName().equalsIgnoreCase(spiName)) {
                    selectedSPI = spi;
                    break;
                }
            }

            if (selectedSPI == null) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("Unable to find a PropertyCollector for this definition: "
                            + spiName);
                }
                continue;
            }

            // property names
            String collectorValue = collector.getValue();
            final String config;
            if (!collectorValue.startsWith(DefaultPropertiesCollectorSPI.REGEX_PREFIX)) {
                config = DefaultPropertiesCollectorSPI.REGEX_PREFIX + collector.getValue();
            } else {
                config = collector.getValue();
            }

            // create the PropertiesCollector
            final PropertiesCollector pc = selectedSPI.create(config,
                    Arrays.asList(collector.getMapped()));
            if (pc != null) {
                pcs.add(pc);
            } else {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("Unable to create PropertyCollector");
                }
            }
        }
        this.propertiesCollectors = pcs;
    }

    void indexingPostamble(final boolean success) throws IOException {
        // close shapefile elements
        if (success) {
            Indexer indexer = runConfiguration.getIndexer();
            boolean supportsEmpty = false;
            if (indexer != null) {
                supportsEmpty = IndexerUtils.getParameterAsBoolean(Prop.CAN_BE_EMPTY, indexer);
            }
            // complete initialization of mosaic configuration
            boolean haveConfigs = configurations != null && !configurations.isEmpty(); 
            if (haveConfigs || supportsEmpty) {

                // We did found some MosaicConfigurations
                Set<String> keys = configurations.keySet();
                int keySize = keys.size();
                if (haveConfigs || !supportsEmpty) {
                    final boolean useName = keySize > 1;
                    for (String key : keys) {
                        MosaicConfigurationBean mosaicConfiguration = configurations.get(key);
                        RasterManager manager = parentReader.getRasterManager(key);
                        manager.initialize(supportsEmpty);
                        // create sample image if the needed elements are available
                        createSampleImage(mosaicConfiguration, useName);
                        eventHandler.fireEvent(Level.INFO, "Creating final properties file ", 99.9);
                        createPropertiesFiles(mosaicConfiguration);
                    }
                }
                final String base = FilenameUtils.getName(parent.getAbsolutePath());
                // we create a root properties file if we have more than one coverage, or if the
                // one coverage does not have the default name
                if (supportsEmpty || keySize > 1 || (keySize > 0 && !base.equals(keys.iterator().next()))) {
                    File mosaicFile = null;
                    File originFile = null;
                    if (indexerFile.getAbsolutePath().endsWith("xml")) {
                        mosaicFile = new File(indexerFile.getAbsolutePath().replace(Utils.INDEXER_XML, (base + ".xml")));
                        originFile = indexerFile;
                    } else if (indexerFile.getAbsolutePath().endsWith("properties")) {
                        mosaicFile = new File(indexerFile.getAbsolutePath().replace(Utils.INDEXER_PROPERTIES, (base + ".properties")));
                        originFile = indexerFile;
                    } else {
                        final String source = runConfiguration.getParameter(Prop.ROOT_MOSAIC_DIR)
                                + File.separatorChar + configurations.get(keys.iterator().next()).getName() + ".properties";
                        mosaicFile = new File(indexerFile.getAbsolutePath().replace(Utils.INDEXER_PROPERTIES, (base + ".properties")));
                        originFile = new File(source);
                    }
                    if (!mosaicFile.exists()) {
                        FileUtils.copyFile(originFile, mosaicFile);
                    }
                }

                // processing information
                eventHandler.fireEvent(Level.FINE, "Done!!!", 100);

            } else {
                // processing information
                eventHandler.fireEvent(Level.FINE, "Nothing to process!!!", 100);
            }
        } else {
            // processing information
            eventHandler.fireEvent(Level.FINE, "Canceled!!!", 100);
        }
    }

    /**
     * Store a sample image frmo which we can derive the default SM and CM
     */
    private void createSampleImage(final MosaicConfigurationBean mosaicConfiguration,
            final boolean useName) {
        // create a sample image to store SM and CM
        Utilities.ensureNonNull("mosaicConfiguration", mosaicConfiguration);
        String filePath = null;
        if (mosaicConfiguration.getSampleModel() != null
                && mosaicConfiguration.getColorModel() != null) {

            // sample image file
            // TODO: Consider revisit this using different name/folder
            final String baseName = runConfiguration.getParameter(Prop.ROOT_MOSAIC_DIR) + "/";
            filePath = baseName + (useName ? mosaicConfiguration.getName() : "") + Utils.SAMPLE_IMAGE_NAME;
            try {
                Utils.storeSampleImage(new File(filePath), mosaicConfiguration.getSampleModel(),
                        mosaicConfiguration.getColorModel());
            } catch (IOException e) {
                eventHandler.fireEvent(Level.SEVERE, e.getLocalizedMessage(), 0);
            }
        }
    }

    private Indexer createIndexer(Properties props, ParametersType params) {
        // Initializing Indexer objects
        Indexer indexer = Utils.OBJECT_FACTORY.createIndexer();
        indexer.setParameters(params != null ? params : Utils.OBJECT_FACTORY.createParametersType());
        Coverages coverages = Utils.OBJECT_FACTORY.createIndexerCoverages();
        indexer.setCoverages(coverages);
        List<Coverage> coverageList = coverages.getCoverage();

        Coverage coverage = Utils.OBJECT_FACTORY.createIndexerCoveragesCoverage();
        coverageList.add(coverage);

        indexer.setParameters(params);
        List<Parameter> parameters = params.getParameter();

        // name
        if (props.containsKey(Prop.NAME)) {
            IndexerUtils.setParam(parameters, props, Prop.NAME);
            coverage.setName(props.getProperty(Prop.NAME));
        }

        // type name
        if (props.containsKey(Prop.TYPENAME)) {
            IndexerUtils.setParam(parameters, props, Prop.TYPENAME);
            coverage.setName(props.getProperty(Prop.TYPENAME));
        }

        // absolute
        if (props.containsKey(Prop.ABSOLUTE_PATH))
            IndexerUtils.setParam(parameters, props, Prop.ABSOLUTE_PATH);

        // recursive
        if (props.containsKey(Prop.RECURSIVE))
            IndexerUtils.setParam(parameters, props, Prop.RECURSIVE);

        // wildcard
        if (props.containsKey(Prop.WILDCARD))
            IndexerUtils.setParam(parameters, props, Prop.WILDCARD);

        // schema
        if (props.containsKey(Prop.SCHEMA)) {
            SchemasType schemas = Utils.OBJECT_FACTORY.createSchemasType();
            SchemaType schema = Utils.OBJECT_FACTORY.createSchemaType();
            indexer.setSchemas(schemas);
            schemas.getSchema().add(schema);
            schema.setAttributes(props.getProperty(Prop.SCHEMA));
            schema.setName(IndexerUtils.getParameter(Prop.INDEX_NAME, indexer));
        }

        DomainsType domains = coverage.getDomains();
        List<DomainType> domainList = null;
        // time attr
        if (props.containsKey(Prop.TIME_ATTRIBUTE)) {
            if (domains == null) {
                domains = Utils.OBJECT_FACTORY.createDomainsType();
                coverage.setDomains(domains);
                domainList = domains.getDomain();
            }
            DomainType domain = Utils.OBJECT_FACTORY.createDomainType();
            domain.setName(Utils.TIME_DOMAIN.toLowerCase());
            IndexerUtils.setAttributes(domain, props.getProperty(Prop.TIME_ATTRIBUTE));
            domainList.add(domain);
        }

        // elevation attr
        if (props.containsKey(Prop.ELEVATION_ATTRIBUTE)) {
            if (domains == null) {
                domains = Utils.OBJECT_FACTORY.createDomainsType();
                coverage.setDomains(domains);
                domainList = domains.getDomain();
            }
            DomainType domain = Utils.OBJECT_FACTORY.createDomainType();
            domain.setName(Utils.ELEVATION_DOMAIN.toLowerCase());
            IndexerUtils.setAttributes(domain, props.getProperty(Prop.ELEVATION_ATTRIBUTE));
            domainList.add(domain);
        }

        // Additional domain attr
        if (props.containsKey(Prop.ADDITIONAL_DOMAIN_ATTRIBUTES)) {
            if (domains == null) {
                domains = Utils.OBJECT_FACTORY.createDomainsType();
                coverage.setDomains(domains);
                domainList = domains.getDomain();
            }
            String attributes = props.getProperty(Prop.ADDITIONAL_DOMAIN_ATTRIBUTES);
            IndexerUtils.parseAdditionalDomains(attributes, domainList);
        }

        // imposed BBOX
        if (props.containsKey(Prop.ENVELOPE2D))
            IndexerUtils.setParam(parameters, props, Prop.ENVELOPE2D);

        // imposed Pyramid Layout
        if (props.containsKey(Prop.RESOLUTION_LEVELS))
            IndexerUtils.setParam(parameters, props, Prop.RESOLUTION_LEVELS);

        // collectors
        if (props.containsKey(Prop.PROPERTY_COLLECTORS)) {
            IndexerUtils
                    .setPropertyCollectors(indexer, props.getProperty(Prop.PROPERTY_COLLECTORS));
        }

        if (props.containsKey(Prop.CACHING))
            IndexerUtils.setParam(parameters, props, Prop.CACHING);

        if (props.containsKey(Prop.ROOT_MOSAIC_DIR)) {
            // Overriding root mosaic directory
            IndexerUtils.setParam(parameters, props, Prop.ROOT_MOSAIC_DIR);
        }
        
        if (props.containsKey(Prop.INDEXING_DIRECTORIES)) {
            IndexerUtils.setParam(parameters, props, Prop.INDEXING_DIRECTORIES);
        }
        if (props.containsKey(Prop.AUXILIARY_FILE)) {
            IndexerUtils.setParam(parameters, props, Prop.AUXILIARY_FILE);
        }
        if (props.containsKey(Prop.AUXILIARY_DATASTORE_FILE)) {
            IndexerUtils.setParam(parameters, props, Prop.AUXILIARY_DATASTORE_FILE);
        }
        if (props.containsKey(Prop.CAN_BE_EMPTY)) {
            IndexerUtils.setParam(parameters, props, Prop.CAN_BE_EMPTY);
        }
        if (props.containsKey(Prop.WRAP_STORE)) {
            IndexerUtils.setParam(parameters, props, Prop.WRAP_STORE);
        }
        if (props.containsKey(Prop.USE_EXISTING_SCHEMA)) {
            IndexerUtils.setParam(parameters, props, Prop.USE_EXISTING_SCHEMA);
        }
        if (props.containsKey(Prop.CHECK_AUXILIARY_METADATA)) {
            IndexerUtils.setParam(parameters, props, Prop.CHECK_AUXILIARY_METADATA);
        }
        return indexer;
    }

    /**
     * Creates the final properties file.
     */
    private void createPropertiesFiles(MosaicConfigurationBean mosaicConfiguration) {

        //
        // FINAL STEP
        //
        // CREATING GENERAL INFO FILE
        //

        CatalogConfigurationBean catalogConfigurationBean = mosaicConfiguration
                .getCatalogConfigurationBean();

        // envelope
        final Properties properties = new Properties();
        properties.setProperty(Utils.Prop.ABSOLUTE_PATH,
                Boolean.toString(catalogConfigurationBean.isAbsolutePath()));
        properties.setProperty(Utils.Prop.LOCATION_ATTRIBUTE,
                catalogConfigurationBean.getLocationAttribute());

        // Time
        final String timeAttribute = mosaicConfiguration.getTimeAttribute();
        if (timeAttribute != null) {
            properties.setProperty(Utils.Prop.TIME_ATTRIBUTE,
                    mosaicConfiguration.getTimeAttribute());
        }

        // Elevation
        final String elevationAttribute = mosaicConfiguration.getElevationAttribute();
        if (elevationAttribute != null) {
            properties.setProperty(Utils.Prop.ELEVATION_ATTRIBUTE,
                    mosaicConfiguration.getElevationAttribute());
        }

        // Additional domains
        final String additionalDomainAttribute = mosaicConfiguration
                .getAdditionalDomainAttributes();
        if (additionalDomainAttribute != null) {
            properties.setProperty(Utils.Prop.ADDITIONAL_DOMAIN_ATTRIBUTES,
                    mosaicConfiguration.getAdditionalDomainAttributes());
        }

        final int numberOfLevels = mosaicConfiguration.getLevelsNum();
        final double[][] resolutionLevels = mosaicConfiguration.getLevels();
        properties.setProperty(Utils.Prop.LEVELS_NUM, Integer.toString(numberOfLevels));
        final StringBuilder levels = new StringBuilder();
        for (int k = 0; k < numberOfLevels; k++) {
            levels.append(Double.toString(resolutionLevels[k][0])).append(",")
                    .append(Double.toString(resolutionLevels[k][1]));
            if (k < numberOfLevels - 1) {
                levels.append(" ");
            }
        }
        properties.setProperty(Utils.Prop.LEVELS, levels.toString());
        properties.setProperty(Utils.Prop.NAME, mosaicConfiguration.getName());
        String typeName = mosaicConfiguration.getCatalogConfigurationBean().getTypeName();
        if (typeName == null) {
            typeName = mosaicConfiguration.getName();
        }
        properties.setProperty(Utils.Prop.TYPENAME, typeName);
        properties.setProperty(Utils.Prop.EXP_RGB,
                Boolean.toString(mosaicConfiguration.isExpandToRGB()));
        properties.setProperty(Utils.Prop.CHECK_AUXILIARY_METADATA,
                Boolean.toString(mosaicConfiguration.isCheckAuxiliaryMetadata()));
        properties.setProperty(Utils.Prop.HETEROGENEOUS,
                Boolean.toString(catalogConfigurationBean.isHeterogeneous()));
        boolean wrapStore = catalogConfigurationBean.isWrapStore();
        if (wrapStore) {
            // Avoid setting this property when false, since it's default
            properties.setProperty(Utils.Prop.WRAP_STORE, Boolean.toString(wrapStore));
        }

        if (cachedReaderSPI != null) {
            // suggested spi
            properties.setProperty(Utils.Prop.SUGGESTED_SPI, cachedReaderSPI.getClass().getName());
        }

        // write down imposed bbox
        if (imposedBBox != null) {
            properties.setProperty(
                    Utils.Prop.ENVELOPE2D,
                    imposedBBox.getMinX() + "," + imposedBBox.getMinY() + " "
                            + imposedBBox.getMaxX() + "," + imposedBBox.getMaxY());
        }
        properties.setProperty(Utils.Prop.CACHING,
                Boolean.toString(catalogConfigurationBean.isCaching()));
        if (mosaicConfiguration.getAuxiliaryFilePath() != null) {
            properties.setProperty(Utils.Prop.AUXILIARY_FILE,
                    mosaicConfiguration.getAuxiliaryFilePath());
        }
        if (mosaicConfiguration.getAuxiliaryDatastorePath() != null) {
            properties.setProperty(Utils.Prop.AUXILIARY_DATASTORE_FILE,
                    mosaicConfiguration.getAuxiliaryDatastorePath());
        }

        OutputStream outStream = null;
        String filePath = runConfiguration.getParameter(Prop.ROOT_MOSAIC_DIR) + "/"
                // + runConfiguration.getIndexName() + ".properties"));
                + mosaicConfiguration.getName() + ".properties"; 
        try {
            outStream = new BufferedOutputStream(new FileOutputStream(filePath));
            properties.store(outStream, "-Automagically created from GeoTools-");
        } catch (FileNotFoundException e) {
            eventHandler.fireEvent(Level.SEVERE, e.getLocalizedMessage(), 0);
        } catch (IOException e) {
            eventHandler.fireEvent(Level.SEVERE, e.getLocalizedMessage(), 0);
        } finally {
            if (outStream != null) {
                IOUtils.closeQuietly(outStream);
            }
        }
    }

    /**
     * Check whether the specified coverage already exist in the reader. This allows to get the rasterManager associated with that coverage instead of
     * creating a new one.
     * 
     * @param coverageName the name of the coverage to be searched
     * @return {@code true} in case that coverage already exists
     * @throws IOException
     */
    protected boolean coverageExists(String coverageName) throws IOException {
        String[] coverages = getParentReader().getGridCoverageNames();
        for (String coverage : coverages) {
            if (coverage.equals(coverageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Use the passed coverageReader to create or update the all the needed configurations<br/>
     * It not responsible of the passed coverageReader which should be disposed outside (in the caller).
     * 
     * @param coverageReader
     * @param inputCoverageName
     * @param fileBeingProcessed
     * @param fileIndex
     * @param numFiles
     * @param transaction
     * @throws IOException
     */
    public void updateConfiguration(GridCoverage2DReader coverageReader,
            final String inputCoverageName, File fileBeingProcessed, int fileIndex,
            double numFiles, DefaultTransaction transaction) throws IOException {

        final String indexName = getRunConfiguration().getParameter(Prop.INDEX_NAME);
        final String coverageName = coverageReader instanceof StructuredGridCoverage2DReader ? inputCoverageName
                : indexName;

        final Indexer indexer = getRunConfiguration().getIndexer();

        // checking whether the coverage already exists
        final boolean coverageExists = coverageExists(coverageName);
        MosaicConfigurationBean mosaicConfiguration = null;
        MosaicConfigurationBean currentConfigurationBean = null;
        RasterManager rasterManager = null;
        if (coverageExists) {

            // Get the manager for this coverage so it can be updated
            rasterManager = getParentReader().getRasterManager(coverageName);
            mosaicConfiguration = rasterManager.getConfiguration();
        }

        // STEP 2
        // Collecting all Coverage properties to setup a MosaicConfigurationBean through
        // the builder
        final MosaicBeanBuilder configBuilder = new MosaicBeanBuilder();

        final GeneralEnvelope envelope = coverageReader
                .getOriginalEnvelope(inputCoverageName);
        final CoordinateReferenceSystem actualCRS = coverageReader
                .getCoordinateReferenceSystem(inputCoverageName);

        SampleModel sm = null;
        ColorModel cm = null;
        int numberOfLevels = 1;
        double[][] resolutionLevels = null;
        CatalogBuilderConfiguration catalogConfig;
        if (mosaicConfiguration == null) {
            catalogConfig = getRunConfiguration();
            // We don't have a configuration for this configuration

            // Get the type specifier for this image and the check that the
            // image has the correct sample model and color model.
            // If this is the first cycle of the loop we initialize everything.
            //
            ImageLayout layout = coverageReader.getImageLayout(inputCoverageName);
            cm = layout.getColorModel(null);
            sm = layout.getSampleModel(null);
            numberOfLevels = coverageReader.getNumOverviews(inputCoverageName) + 1;
            resolutionLevels = coverageReader.getResolutionLevels(inputCoverageName);

            // at the first step we initialize everything that we will
            // reuse afterwards starting with color models, sample
            // models, crs, etc....

            configBuilder.setSampleModel(sm);
            configBuilder.setColorModel(cm);
            ColorModel defaultCM = cm;

            // Checking palette
            if (defaultCM instanceof IndexColorModel) {
                IndexColorModel icm = (IndexColorModel) defaultCM;
                byte[][] defaultPalette = Utils.extractPalette(icm);
                configBuilder.setPalette(defaultPalette);
            }

            // STEP 2.A
            // Preparing configuration
            configBuilder.setCrs(actualCRS);
            configBuilder.setLevels(resolutionLevels);
            configBuilder.setLevelsNum(numberOfLevels);
            configBuilder.setName(coverageName);
            configBuilder.setTimeAttribute(IndexerUtils.getAttribute(coverageName,
                    Utils.TIME_DOMAIN, indexer));
            configBuilder.setElevationAttribute(IndexerUtils.getAttribute(coverageName,
                    Utils.ELEVATION_DOMAIN, indexer));
            configBuilder.setAdditionalDomainAttributes(IndexerUtils.getAttribute(coverageName,
                    Utils.ADDITIONAL_DOMAIN, indexer));

            final Hints runHints = getRunConfiguration().getHints();
            if (runHints != null) {
                if (runHints.containsKey(Utils.AUXILIARY_FILES_PATH)) {
                    String auxiliaryFilePath = (String) runHints.get(Utils.AUXILIARY_FILES_PATH);
                    if (auxiliaryFilePath != null && auxiliaryFilePath.trim().length() > 0) {
                        configBuilder.setAuxiliaryFilePath(auxiliaryFilePath);
                    }
                }
                if (runHints.containsKey(Utils.AUXILIARY_DATASTORE_PATH)) {
                    String auxiliaryDatastorePath = (String) runHints.get(Utils.AUXILIARY_DATASTORE_PATH);
                    if (auxiliaryDatastorePath != null && auxiliaryDatastorePath.trim().length() > 0) {
                        configBuilder.setAuxiliaryDatastorePath(auxiliaryDatastorePath);
                    }
                }
            }

            final CatalogConfigurationBean catalogConfigurationBean = new CatalogConfigurationBean();
            catalogConfigurationBean.setCaching(IndexerUtils.getParameterAsBoolean(Prop.CACHING,
                    indexer));
            catalogConfigurationBean.setAbsolutePath(IndexerUtils.getParameterAsBoolean(
                    Prop.ABSOLUTE_PATH, indexer));

            catalogConfigurationBean.setLocationAttribute(IndexerUtils.getParameter(
                    Prop.LOCATION_ATTRIBUTE, indexer));
            catalogConfigurationBean.setWrapStore(IndexerUtils.getParameterAsBoolean(
                    Prop.WRAP_STORE, indexer));

            String configuredTypeName = IndexerUtils.getParameter(Prop.TYPENAME, indexer);
            if (configuredTypeName != null) {
                catalogConfigurationBean.setTypeName(configuredTypeName);
            } else {
                catalogConfigurationBean.setTypeName(coverageName);
            }
            configBuilder.setCatalogConfigurationBean(catalogConfigurationBean);
            configBuilder.setCheckAuxiliaryMetadata(IndexerUtils.getParameterAsBoolean(Prop.CHECK_AUXILIARY_METADATA, indexer));

            currentConfigurationBean = configBuilder.getMosaicConfigurationBean();

            // Creating a rasterManager which will be initialized after populating the catalog
            rasterManager = getParentReader().addRasterManager(currentConfigurationBean, false);

            // Creating a granuleStore
            if (!useExistingSchema) {
                // creating the schema
                SimpleFeatureType indexSchema = createSchema(getRunConfiguration(),
                        currentConfigurationBean.getName(), actualCRS);
                getParentReader().createCoverage(coverageName, indexSchema);
//            } else {
//                rasterManager.typeName = coverageName;
            }
            getConfigurations().put(currentConfigurationBean.getName(), currentConfigurationBean);

        } else {
            catalogConfig = new CatalogBuilderConfiguration();
            CatalogConfigurationBean bean = mosaicConfiguration.getCatalogConfigurationBean();
            catalogConfig.setParameter(Prop.LOCATION_ATTRIBUTE, (bean.getLocationAttribute()));
            catalogConfig.setParameter(Prop.ABSOLUTE_PATH, Boolean.toString(bean.isAbsolutePath()));
            catalogConfig.setParameter(Prop.ROOT_MOSAIC_DIR/* setRootMosaicDirectory( */,
                    getRunConfiguration().getParameter(Prop.ROOT_MOSAIC_DIR));

            // We already have a Configuration for this coverage.
            // Check its properties are compatible with the existing coverage.

            CatalogConfigurationBean catalogConfigurationBean = bean;

            // make sure we pick the same resolution irrespective of order of harvest
            numberOfLevels = coverageReader.getNumOverviews(inputCoverageName) + 1;
            resolutionLevels = coverageReader.getResolutionLevels(inputCoverageName);
            
            int originalNumberOfLevels = mosaicConfiguration.getLevelsNum();
            boolean needUpdate = false;
            if (Utils.homogeneousCheck(Math.min(numberOfLevels, originalNumberOfLevels), 
                    resolutionLevels, mosaicConfiguration.getLevels())) {
                if (numberOfLevels != originalNumberOfLevels) {
                    catalogConfigurationBean.setHeterogeneous(true);
                    if (numberOfLevels > originalNumberOfLevels) {
                        needUpdate = true; // pick the one with highest number of levels
                    }
                }
            } else {
                catalogConfigurationBean.setHeterogeneous(true);
                if (isHigherResolution(resolutionLevels, mosaicConfiguration.getLevels())) {
                    needUpdate = true; // pick the one with the highest resolution
                }
            }

            // configuration need to be updated
            if (needUpdate) {
                mosaicConfiguration.setLevels(resolutionLevels);
                mosaicConfiguration.setLevelsNum(numberOfLevels);
                getConfigurations().put(mosaicConfiguration.getName(), mosaicConfiguration);
            }

            ImageLayout layout = coverageReader.getImageLayout(inputCoverageName);
            cm = layout.getColorModel(null);
            sm = layout.getSampleModel(null);

            // comparing ColorModel
            // comparing SampeModel
            // comparing CRSs
            ColorModel actualCM = cm;
            CoordinateReferenceSystem expectedCRS;
            if (mosaicConfiguration.getCrs() != null) {
                expectedCRS = mosaicConfiguration.getCrs();
            } else {
                expectedCRS = rasterManager.spatialDomainManager.coverageCRS;
            }
            if (!(CRS.equalsIgnoreMetadata(expectedCRS, actualCRS))) {
                // if ((fileIndex > 0 ? !(CRS.equalsIgnoreMetadata(defaultCRS, actualCRS)) : false)) {
                eventHandler.fireFileEvent(Level.INFO, fileBeingProcessed, false, "Skipping image "
                        + fileBeingProcessed + " because CRSs do not match.",
                        (((fileIndex + 1) * 99.0) / numFiles));
                return;
            }

            byte[][] palette = mosaicConfiguration.getPalette();
            ColorModel colorModel = mosaicConfiguration.getColorModel();
            if (colorModel == null) {
                colorModel = rasterManager.defaultCM;
            }
            if (palette == null) {
                palette = rasterManager.defaultPalette;
            }
            if (Utils.checkColorModels(colorModel, palette, actualCM)) {
                eventHandler.fireFileEvent(Level.INFO, fileBeingProcessed, false, "Skipping image "
                        + fileBeingProcessed + " because color models do not match.",
                        (((fileIndex + 1) * 99.0) / numFiles));
                return;
            }

        }
        // STEP 3
        if (!useExistingSchema) {
            // create and store features
            updateCatalog(coverageName, fileBeingProcessed, coverageReader,
                    getParentReader(), catalogConfig, envelope, transaction,
                    getPropertiesCollectors());
        }
    }

    private boolean isHigherResolution(double[][] a, double[][] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            for (int j = 0; i < Math.min(a[i].length, b[i].length); i++) {
                if (a[i][j] < b[i][j]) {
                    return true;
                } else if (a[i][j] > b[i][j]) {
                    return false;
                } 
            }
        } 
        return false;
    }

    public void dispose() {
        reset();
    }

    public Map<String, MosaicConfigurationBean> getConfigurations() {
        return configurations;
    }

    public GranuleCatalog getCatalog() {
        return catalog;
    }

    public CatalogBuilderConfiguration getRunConfiguration() {
        return runConfiguration;
    }

    public ImageMosaicReader getParentReader() {
        return parentReader;
    }

    public void setParentReader(ImageMosaicReader parentReader) {
        this.parentReader = parentReader;
    }

    public List<PropertiesCollector> getPropertiesCollectors() {
        return propertiesCollectors;
    }

    public boolean isUseExistingSchema() {
        return useExistingSchema;
    }


    public ImageReaderSpi getCachedReaderSPI() {
        return cachedReaderSPI;
    }

    public void setCachedReaderSPI(ImageReaderSpi cachedReaderSPI) {
        this.cachedReaderSPI = cachedReaderSPI;
    }
}

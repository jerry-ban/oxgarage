package pl.psnc.dl.ege.tei;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import java.util.zip.ZipOutputStream;
import java.io.BufferedOutputStream;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import net.sf.saxon.s9api.XdmNode;

import org.apache.log4j.Logger;
import org.tei.exceptions.ConfigurationException;
import org.tei.tei.DocXTransformationProperties;
import org.tei.utils.SaxonProcFactory;

import pl.psnc.dl.ege.component.Converter;
import pl.psnc.dl.ege.configuration.EGEConfigurationManager;
import pl.psnc.dl.ege.configuration.EGEConstants;
import pl.psnc.dl.ege.exception.ConverterException;
import pl.psnc.dl.ege.types.ConversionActionArguments;
import pl.psnc.dl.ege.types.DataType;
import pl.psnc.dl.ege.utils.EGEIOUtils;
import pl.psnc.dl.ege.utils.IOResolver;

/**
 * <p>
 * EGE Converter interface implementation
 * </p>
 * 
 * Provides multiple conversions for Enrich TEI format.<br>
 * <b>Important : </b> the converter expects only compressed data. Data is
 * compressed with standard EGE IOResolver received from
 * EGEConfigurationManager.
 * 
 * @author mariuszs
 * 
 */
public class TEIConverter implements Converter {

	private static final String EX_NO_FILE_DATA_WAS_FOUND = "No file data was found for conversion";

	// List of directories which might contain images for input type
	private static final List<String> imagesInputDirectories = Arrays.asList(new String[] {"media", "Pictures"});

	private static final Logger LOGGER = Logger.getLogger(TEIConverter.class);

	public static final String DOCX_ERROR = "Probably trying to convert from DocX with wrong input.";

	private IOResolver ior = EGEConfigurationManager.getInstance()
			.getStandardIOResolver();

	public void convert(InputStream inputStream, OutputStream outputStream,
			final ConversionActionArguments conversionDataTypes)
			throws ConverterException, IOException {
		boolean found = false;
		try {
			for (ConversionActionArguments cadt : ConverterConfiguration.CONVERSIONS) {
				if (conversionDataTypes.equals(cadt)) {
					String profile = cadt.getProperties().get(
							ConverterConfiguration.PROFILE_KEY);
					LOGGER.debug("Converting from : "
							+ conversionDataTypes.getInputType().toString()
							+ " to "
							+ conversionDataTypes.getOutputType().toString());
					LOGGER.debug("Selected profile : " + profile);
					convertDocument(inputStream, outputStream, cadt.getInputType(), cadt.getOutputType(),
							cadt.getProperties());
					found = true;
				}
			}
		} catch (ConfigurationException ex) {
			LOGGER.error(ex.getMessage(), ex);
			throw new ConverterException(ex.getMessage());
		} catch (SaxonApiException ex) {
			// return wrong docx input message
			if (ex.getMessage() != null
					&& ex.getMessage().contains("FileNotFoundException")
					&& conversionDataTypes.getInputType().getFormat().equals(
							Format.DOCX.getFormatName())
					&& conversionDataTypes.getInputType().getMimeType().equals(
							Format.DOCX.getMimeType())) {
				LOGGER.warn(ex.getMessage(), ex);
				throw new ConverterException(DOCX_ERROR);
			}
			LOGGER.error(ex.getMessage(), ex);
			throw new ConverterException(ex.getMessage());
		}
		if (!found) {
			throw new ConverterException(
					ConverterException.UNSUPPORTED_CONVERSION_TYPES);
		}
	}

	/*
	 * Prepares transformation : based on MIME type.
	 */
	private void convertDocument(InputStream inputStream, OutputStream outputStream,
			DataType fromDataType, DataType toDataType, Map<String, String> properties) throws IOException,
			SaxonApiException, ConfigurationException, ConverterException {
		String toMimeType = toDataType.getMimeType();
		String profile = properties.get(ConverterConfiguration.PROFILE_KEY);

		// from DOCX to TEI
		if (ConverterConfiguration.XML_MIME.equals(toMimeType)
				&& toDataType.getFormat().equals(ConverterConfiguration.TEI)
				&& fromDataType.getFormat().equals(Format.DOCX.getId())) {
			if (!ConverterConfiguration.checkProfile(profile, Format.DOCX
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			transformFromDocX(inputStream, outputStream, profile, properties);
		}
		// to DOCX
		else if (Format.DOCX.getMimeType().equals(toMimeType)) {
			if (!ConverterConfiguration.checkProfile(profile, Format.DOCX
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			Processor proc = SaxonProcFactory.getProcessor();
			XsltCompiler comp = proc.newXsltCompiler();
			transformToDocX(inputStream, outputStream, proc, comp, profile, properties);
		}
		// from ODT to TEI
		else if (ConverterConfiguration.XML_MIME.equals(toMimeType)
				&& toDataType.getFormat().equals(ConverterConfiguration.TEI)
				&& fromDataType.getFormat().equals(Format.ODT.getId())) {
			if (!ConverterConfiguration.checkProfile(profile, Format.ODT
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			transformFromOdt(inputStream, outputStream, profile, properties);
		}
		// to ODT
		else if (Format.ODT.getMimeType().equals(toMimeType)) {
			if (!ConverterConfiguration.checkProfile(profile, Format.ODT
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			Processor proc = SaxonProcFactory.getProcessor();
			XsltCompiler comp = proc.newXsltCompiler();
			transformToOdt(inputStream, outputStream, proc, comp, profile, properties);
		}
		// to HTML for ODD
		else if (Format.ODDHTML.getMimeType().equals(toMimeType)
			 && fromDataType.getFormat().equals(Format.ODDHTML.getFormatName())) {
			if (!ConverterConfiguration.checkProfile(profile, Format.ODDHTML
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			properties.put("extension", "html");
			performXsltTransformation(inputStream, outputStream, Format.ODDHTML
					.getProfile(), profile, properties);
		}
		// to XHTML
		else if (Format.XHTML.getMimeType().equals(toMimeType)) {
			if (!ConverterConfiguration.checkProfile(profile, Format.XHTML
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			properties.put("extension", "html");
			performXsltTransformation(inputStream, outputStream, Format.XHTML
					.getProfile(), profile, properties);
		}
		// to RELAXNG
		else if (Format.RELAXNG.getMimeType().equals(toMimeType)) {
			if (!ConverterConfiguration.checkProfile(profile, Format.RELAXNG
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			properties.put("extension", "rng");
			performXsltTransformation(inputStream, outputStream, Format.RELAXNG
					.getProfile(), profile, properties);
		}
		// to DTD
		else if (Format.DTD.getMimeType().equals(toMimeType)) {
			if (!ConverterConfiguration.checkProfile(profile, Format.DTD
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			properties.put("extension", "dtd");
			performXsltTransformation(inputStream, outputStream, Format.DTD
					.getProfile(), profile, properties);
		}
		// to LITE
		else if (Format.LITE.getMimeType().equals(toMimeType) 
			 && fromDataType.getFormat().equals(Format.LITE.getFormatName())) {
			if (!ConverterConfiguration.checkProfile(profile, Format.LITE
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			properties.put("extension", "xml");
			performXsltTransformation(inputStream, outputStream, Format.LITE
					.getProfile(), profile, properties);
		}
		// to LATEX
		else if (Format.LATEX.getMimeType().equals(toMimeType)) {
			if (!ConverterConfiguration.checkProfile(profile, Format.LATEX
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			properties.put("extension", "tex");
			performXsltTransformation(inputStream, outputStream, Format.LATEX
					.getProfile(), profile, properties);
		}
		// to FO
		else if (Format.FO.getMimeType().equals(toMimeType)) {
				//&& Format.FO.getFormatName().equals(dataType.getFormat())) {
			if (!ConverterConfiguration.checkProfile(profile, Format.FO
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			properties.put("extension", "fo");
			performXsltTransformation(inputStream, outputStream, Format.FO
					.getProfile(), profile, properties);
		}
		// to EPUB
		else if (Format.EPUB.getMimeType().equals(toMimeType)) {
			if (!ConverterConfiguration.checkProfile(profile, Format.EPUB
					.getProfile())) {
				LOGGER.warn(ConverterConfiguration.PROFILE_NOT_FOUND_MSG);
				profile = ConverterConfiguration.DEFAULT_PROFILE;
			}
			transformToEpub(inputStream, outputStream, profile, Format.EPUB.getProfile(), properties);
		}
	}

	/*
	 * prepares received data - decompress, search for file to convert and open file stream.
	 */
	private InputStream prepareInputData(InputStream inputStream, File inTempDir)
			throws IOException, ConverterException {
		ior.decompressStream(inputStream, inTempDir);
		File sFile = searchForData(inTempDir, "^.*\\.((?i)xml)$");
		if (sFile == null) {
			//search for any file
			sFile = searchForData(inTempDir, "^.*");
			if(sFile == null){
				throw new ConverterException("No file data was found for conversion");
			}
		}
		FileInputStream fis = new FileInputStream(sFile);
		return fis;
	}

	/*
	 * prepares received data - decompress and open file stream, doesn't search for xml file, it's supplied as argument
	 */
	private InputStream prepareInputData(InputStream inputStream, File inTempDir, File inputFile)
			throws IOException, ConverterException {
		if (inputFile == null) {
			//search for any file
			inputFile = searchForData(inTempDir, "^.*");
			if(inputFile == null){
				throw new ConverterException("No file data was found for conversion");
			}
		}
		FileInputStream fis = new FileInputStream(inputFile);
		return fis;
	}
	
	/*
	 * Search for specified by regex file 
	 */
	private File searchForData(File dir, String regex) {
		for (File f : dir.listFiles()) {
			if (!f.isDirectory() && Pattern.matches(regex, f.getName())) {
				return f;
			} else if (f.isDirectory() && !imagesInputDirectories.contains(f.getName())) {
				File sf = searchForData(f, regex);
				if (sf != null) {
					return sf;
				}
			}
		}
		return null;
	}
	
	private File prepareTempDir() {
		File inTempDir = null;
		String uid = UUID.randomUUID().toString();
		inTempDir = new File(EGEConstants.TEMP_PATH + File.separator + uid
				+ File.separator);
		inTempDir.mkdir();
		return inTempDir;
	}

	/**
	 * Decompress zips containing images
	 */
	private void prepareImages(File imageDir) throws IOException {
		File sFile = searchForData(imageDir, "^.*\\.((?i)zip)$");
		ZipFile zipFile = null;
		File zipOutputDir = null;
		while (sFile!=null) {
			try { 
				zipFile = new ZipFile(sFile);
				
				zipOutputDir = new File(imageDir + File.separator + sFile.getName().replace('.', '-') + File.separator);
				zipOutputDir.mkdir();
				EGEIOUtils.unzipFile(zipFile, zipOutputDir);
				sFile.delete();
				sFile = searchForData(imageDir, "^.*\\.((?i)zip)$");
			}
			catch (Exception e) {
				throw new IOException("Some of the zip archives were damaged: " + e.toString());
			}
		}
	}

	/*
	 * Performs transformation over XSLT 
	 */
	private void performXsltTransformation(InputStream inputStream,
			OutputStream outputStream, String id, String profile, Map<String, String> properties)
			throws IOException, SaxonApiException, ConverterException {
		FileOutputStream fos = null;
		InputStream is = null;
		File inTmpDir = null;
		File outTempDir = null;
		File outputDir = null;
		try {
			inTmpDir = prepareTempDir();
			ior.decompressStream(inputStream, inTmpDir);
			File inputFile = searchForData(inTmpDir, "^.*");
			outTempDir = prepareTempDir();
			is = prepareInputData(inputStream, inTmpDir, inputFile);
			Processor proc = SaxonProcFactory.getProcessor();
			XsltCompiler comp = proc.newXsltCompiler();
			// get images and correct graphics tags
			XdmNode initialNode = getImages(inTmpDir.toString(), outTempDir.toString(), "media" + File.separator, 
							"media" + File.separator, inputFile, proc, is, "Xslt", properties);
			String extension = properties.get("extension");
			File resFile = new File(outTempDir + File.separator + "document." + extension);
			fos = new FileOutputStream(resFile);
			XsltExecutable exec = comp.compile(resolveConfiguration(id, comp, profile));
			XsltTransformer transformer = exec.load();
			setTransformationParameters(transformer, id);
			transformer.setInitialContextNode(initialNode);
			Serializer result = new Serializer();
			result.setOutputStream(fos);
			transformer.setDestination(result);
			transformer.transform();
			ior.compressData(outTempDir, outputStream);
		} finally {
			try {
				is.close();
			} catch (Exception ex) {
				// do nothing
			}
			try {
				fos.close();
			} catch (Exception ex) {
				// do nothing
			}
			if (outTempDir != null && outTempDir.exists())
				EGEIOUtils.deleteDirectory(outTempDir);
			if (inTmpDir != null && inTmpDir.exists())
				EGEIOUtils.deleteDirectory(inTmpDir);
		}

	}

	/*
	 * Additional parameters for XHTML transformation.
	 */
	private void setTransformationParameters(XsltTransformer transformer,
			String id) {
		if (Format.XHTML.getId().equals(id)) {
			transformer.setParameter(new QName("STDOUT"), new XdmAtomicValue(
					"true"));
			transformer.setParameter(new QName("splitLevel"),
					new XdmAtomicValue("-1"));
			transformer.setParameter(new QName("lang"),
					new XdmAtomicValue("en"));
			transformer.setParameter(new QName("doclang"), new XdmAtomicValue(
					"en"));
			transformer.setParameter(new QName("documentationLanguage"),
					new XdmAtomicValue("en"));
			transformer.setParameter(new QName("institution"),
					new XdmAtomicValue(""));
		}
	}
	
	/*
	 * Performs from DocX to TEI transformation
	 */
	private void transformFromDocX(InputStream is, OutputStream os,
			String profile, Map<String, String> properties) throws IOException, SaxonApiException,
			ConfigurationException, ConverterException {
		File tmpDir = prepareTempDir();
		InputStream fis = null;
		String fileName = properties.get("fileName");
		ComplexConverter docX = new DocXConverter(profile, fileName);
		try {
			ior.decompressStream(is, tmpDir);
			// should contain only single file
			File docXFile = searchForData(tmpDir, "^.*\\.((?i)doc|(?i)docx)$");
			if (docXFile == null) {
				docXFile = searchForData(tmpDir, "^.*");
				if (docXFile == null) {
					throw new ConverterException(EX_NO_FILE_DATA_WAS_FOUND);
				}
			}
			fis = new FileInputStream(docXFile);
			docX.toTEI(fis, os);
		} finally {
			if(fis != null){
				try{
					fis.close();
				}catch(Exception ex){
					// do nothing
				}
			}
			if (tmpDir != null) {
				EGEIOUtils.deleteDirectory(tmpDir);
			}
			if(docX != null){
				docX.cleanUp();
			}
		}
	}
	
	/*
	 * Performs From TEI to DocX transformation
	 */
	private void transformToDocX(InputStream is, OutputStream os,
			Processor proc, XsltCompiler comp, final String profile, Map<String, String> properties)
			throws IOException, SaxonApiException, ConfigurationException,
			ConverterException {
		File inTmpDir = prepareTempDir();
		File outTmpDir = prepareTempDir();
		ior.decompressStream(is, inTmpDir);
		File inputFile = searchForData(inTmpDir, "^.*");
		InputStream inputStream = prepareInputData(is, inTmpDir, inputFile);
		ComplexConverter docX = null;
		FileOutputStream fos = null;
		try {
			docX = new DocXConverter(profile);
			// get images and correct graphics tags
			XdmNode initialNode = getImages(inTmpDir.toString(), docX.getDirectoryName(), docX.getImagesDirectoryName(), 
						docX.getImagesDirectoryNameRelativeToDocument(), inputFile, proc, inputStream, "toDocx", properties);
			// perform conversion
			docX.mergeTEI(initialNode);
			File oDocXFile = new File(outTmpDir.getAbsolutePath() + File.separator + "result.docx");
			fos = new FileOutputStream(oDocXFile);
			// pack directory to final DocX file
			docX.zipToStream(fos, new File(docX.getDirectoryName()));
			// double compress DocX file anyway
			ior.compressData(outTmpDir, os);
			// clean temporary files
		} finally {
			// perform cleanup
			try{
				inputStream.close();
			}catch(Exception ex){
				// do nothing
			}
			if(fos != null){
				try{
					fos.close();
				}catch(Exception ex){
					// do nothing
				}
			}
			if(docX != null){
				docX.cleanUp();
			}
			EGEIOUtils.deleteDirectory(inTmpDir);
			EGEIOUtils.deleteDirectory(outTmpDir);
		}
	}

	private void transformFromOdt(InputStream is, OutputStream os,
			String profile, Map<String, String> properties) throws IOException, SaxonApiException,
			ConfigurationException, ConverterException {
		File tmpDir = prepareTempDir();
		InputStream fis = null;
		String fileName = properties.get("fileName");
		ComplexConverter odt = new OdtConverter(profile, fileName);
		try {
			ior.decompressStream(is, tmpDir);
			// should contain only single file
			File odtFile = searchForData(tmpDir, "^.*\\.((?i)odt|(?i)ott)$");
			if (odtFile == null) {
				odtFile = searchForData(tmpDir, "^.*");
				if (odtFile == null) {
					throw new ConverterException(EX_NO_FILE_DATA_WAS_FOUND);
				}
			}
			fis = new FileInputStream(odtFile);
			odt.toTEI(fis, os);
		} finally {
			if(fis != null){
				try{
					fis.close();
				}catch(Exception ex){
					// do nothing
				}
			}
			if (tmpDir != null) {
				EGEIOUtils.deleteDirectory(tmpDir);
			}
			if(odt != null){
				odt.cleanUp();
			}
		}
	}

	private void transformToOdt(InputStream is, OutputStream os,
			Processor proc, XsltCompiler comp, final String profile, Map<String, String> properties)
			throws IOException, SaxonApiException, ConfigurationException,
			ConverterException {
		File inTmpDir = prepareTempDir();
		File outTmpDir = prepareTempDir();
		ior.decompressStream(is, inTmpDir);
		File inputFile = searchForData(inTmpDir, "^.*");
		InputStream inputStream = prepareInputData(is, inTmpDir, inputFile);
		ComplexConverter odt = null;
		FileOutputStream fos = null;
		// assign properties
		try {
			odt = new OdtConverter(profile);
			// get images and correct graphics tags
			XdmNode initialNode = getImages(inTmpDir.toString(), odt.getDirectoryName(), odt.getImagesDirectoryName(), 
						odt.getImagesDirectoryNameRelativeToDocument(), inputFile, proc, inputStream, "toOdt", properties);
			// perform conversion
			odt.mergeTEI(initialNode);
			File oOdtFile = new File(outTmpDir.getAbsolutePath() + File.separator + "result.odt");
			fos = new FileOutputStream(oOdtFile);
			// pack directory to final Odt file
			odt.zipToStream(fos, new File(odt.getDirectoryName()));
			// double compress Odt file anyway
			ior.compressData(outTmpDir, os);
			// clean temporary files
		} finally {
			// perform cleanup
			try{
				inputStream.close();
			}catch(Exception ex){
				// do nothing
			}
			if(fos != null){
				try{
					fos.close();
				}catch(Exception ex){
					// do nothing
				}
			}
			if(odt != null){
				odt.cleanUp();
			}
			EGEIOUtils.deleteDirectory(inTmpDir);
			EGEIOUtils.deleteDirectory(outTmpDir);
		}
	}

	public void transformToEpub(InputStream inputStream, OutputStream outputStream,
			final String profile, String id, Map<String, String> properties)
			throws IOException, SaxonApiException, ConfigurationException,
			ConverterException {
		FileOutputStream fos = null;
		InputStream is = null;
		File inTmpDir = null;
		File outTempDir = null;
		File outputDir = null;
		try {
			inTmpDir = prepareTempDir();
			ior.decompressStream(inputStream, inTmpDir);
			File inputFile = searchForData(inTmpDir, "^.*");
			outTempDir = prepareTempDir();
			is = prepareInputData(inputStream, inTmpDir, inputFile);
			Processor proc = SaxonProcFactory.getProcessor();
			XsltCompiler comp = proc.newXsltCompiler();
			// get images and correct graphics tags
			XdmNode initialNode = getImages(inTmpDir.toString(), outTempDir.toString(), "OEBPS" + File.separator + "media" + 
							File.separator, "media" + File.separator, inputFile, proc, is, "toEpub", properties);
			XsltExecutable exec = comp.compile(resolveConfiguration(id, comp, profile));
			XsltTransformer transformer = exec.load();
			String dirname = outTempDir.toURI().toString();
			transformer.setParameter(new QName("directory"), new XdmAtomicValue(dirname));
			transformer.setParameter(new QName("outputDir"), new XdmAtomicValue(dirname + File.separator + "OEBPS" + File.separator));
			File coverTemplate = new File (ConverterConfiguration.PATH + File.separator + 
			"tei-config"+ File.separator + "stylesheets" + File.separator +  "profiles" + File.separator + profile + File.separator + "epub" + File.separator + "cover.jpg");
			String coverOutputDir = outTempDir + File.separator + "OEBPS" + File.separator;
			String coverImage = ImageFetcher.generateCover(coverTemplate, coverOutputDir, properties);
			transformer.setParameter(new QName("coverimage"), new XdmAtomicValue(coverImage));
			setTransformationParameters(transformer, id);
			transformer.setInitialContextNode(initialNode);
			Serializer result = new Serializer();
			transformer.setDestination(result);
			transformer.transform();
			outputDir = prepareTempDir();
			File oEpubFile = new File(outputDir.getAbsolutePath() + File.separator + "result.epub");
			fos = new FileOutputStream(oEpubFile);
			// pack directory to final Epub file
			ZipOutputStream zipOs = new ZipOutputStream(
				new BufferedOutputStream(fos));
			// zip it with mimetype on first position and uncompressed
			File mimetype = new File(outTempDir + File.separator + "mimetype");
			EGEIOUtils.constructZip(outTempDir, zipOs, "", mimetype);
			zipOs.close();
			// double compress Ebup file anyway
			ior.compressData(outputDir, outputStream);
			// clean temporary files
		}
		finally {
			try {
				is.close();
			} catch (Exception ex) {
				// do nothing
			}
			if(fos != null){
				try{
					fos.close();
				}catch(Exception ex){
					// do nothing
				}
			}
			if (outTempDir != null && outTempDir.exists())
				EGEIOUtils.deleteDirectory(outTempDir);
			if (inTmpDir != null && inTmpDir.exists())
				EGEIOUtils.deleteDirectory(inTmpDir);
			if (outputDir != null && outputDir.exists())
				EGEIOUtils.deleteDirectory(outputDir);
		}
	}

	private XdmNode getImages(String inputTempDir, String outputTemp, String outputImgDir, String imgDirRelativeToDoc, 
					File inputFile, Processor proc, InputStream is, String conversion, Map<String,String> properties)
			throws IOException, SaxonApiException, ConverterException {
		File inputImages = null;
		boolean getImages = true;
		boolean downloadImages = true;
		boolean textOnly = false;
		if(properties.get(ConverterConfiguration.IMAGES_KEY)!=null) 
			getImages = properties.get(ConverterConfiguration.IMAGES_KEY).equals("true");
		if(properties.get(ConverterConfiguration.FETCHIMAGES_KEY)!=null)
			downloadImages = properties.get(ConverterConfiguration.FETCHIMAGES_KEY).equals("true");
		if(properties.get(ConverterConfiguration.TEXTONLY_KEY)!=null) 
			textOnly = properties.get(ConverterConfiguration.TEXTONLY_KEY).equals("true");
		for(String imageDir : imagesInputDirectories) {
			inputImages = new File(inputTempDir + File.separator + imageDir + File.separator);			
			if(inputImages.exists()) {
				// there are images to copy
				prepareImages(inputImages);
				File outputImages = new File(outputTemp + File.separator + outputImgDir);
				outputImages.mkdirs();
				return ImageFetcher.getChangedNode(inputFile, outputImgDir, imgDirRelativeToDoc, 
									inputImages, outputImages, conversion, 
									properties);
			}
		}
		File outputImages = new File(outputTemp + File.separator + outputImgDir);
		outputImages.mkdirs();		
		return ImageFetcher.getChangedNode(inputFile, outputImgDir, imgDirRelativeToDoc, 
								null, outputImages, conversion, 
								properties);
	}

	/*
	 * Setups new URIResolver for XSLT compiler and returns StreamSource of XSL
	 * transform scheme.
	 */
	private StreamSource resolveConfiguration(final String id,
			XsltCompiler comp, String profile) throws IOException {
		comp.setURIResolver(TEIConverterURIResolver
				.newInstance(ConverterConfiguration.PATH + "/" + "tei-config"
						+ "/" + "stylesheets" + "/" + "profiles" + "/"
						+ profile + "/" + id));
		return new StreamSource(new FileInputStream(new File(
				ConverterConfiguration.STYLESHEETS_PATH + "profiles"
						+ File.separator + profile + File.separator + id
						+ File.separator + "to.xsl")));
	}

	public List<ConversionActionArguments> getPossibleConversions() {
		return (List<ConversionActionArguments>) ConverterConfiguration.CONVERSIONS;
	}
}
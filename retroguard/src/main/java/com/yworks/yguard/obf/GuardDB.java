/*
 * YGuard -- an obfuscation library for Java(TM) classfiles.
 *
 * Original Copyright (c) 1999 Mark Welsh (markw@retrologic.com)
 * Modifications Copyright (c) 2002 yWorks GmbH (yguard@yworks.com)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * The author may be contacted at yguard@yworks.com
 *
 * Java and all Java-based marks are trademarks or registered
 * trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 */
package com.yworks.yguard.obf;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.jar.*;
import java.security.*;

import com.yworks.yguard.*;
import com.yworks.yguard.obf.classfile.*;

import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Classfile database for obfuscation.
 *
 * @author      Mark Welsh
 */
public class GuardDB implements ClassConstants
{
  // Constants -------------------------------------------------------------
  private static final String STREAM_NAME_MANIFEST = "META-INF/MANIFEST.MF";
  private static final String MANIFEST_NAME_TAG = "Name";
  private static final String MANIFEST_DIGESTALG_TAG = "Digest-Algorithms";
  private static final String CLASS_EXT = ".class";
  private static final String SIGNATURE_PREFIX = "META-INF/";
  private static final String SIGNATURE_EXT = ".SF";
  private static final String LOG_MEMORY_USED = "  Memory in use after class data structure built: ";
  private static final String LOG_MEMORY_TOTAL = "  Total memory available                        : ";
  private static final String LOG_MEMORY_BYTES = " bytes";
  private static final String WARNING_SCRIPT_ENTRY_ABSENT = "<!-- WARNING - identifier from script file not found in JAR: ";
  private static final String ERROR_CORRUPT_CLASS = "<!-- ERROR - corrupt class file: ";


  // Fields ----------------------------------------------------------------
  private JarFile[] inJar;          // JAR file for obfuscation
  private Manifest[] oldManifest;   // MANIFEST.MF
  private Manifest[] newManifest;   // MANIFEST.MF
  private ClassTree classTree;    // Tree of packages, classes. methods, fields
  private boolean hasMap = false;

  /** Utility field holding list of Listeners. */
  private transient java.util.ArrayList listenerList;

  /** Holds value of property replaceClassNameStrings. */
  private boolean replaceClassNameStrings;

  /** Holds value of property pedantic. */
  private boolean pedantic;

  private ResourceHandler resourceHandler;
  private String[] digestStrings;

  // Has the mapping been generated already?

  // Class Methods ---------------------------------------------------------

  // Instance Methods ------------------------------------------------------
  /** A classfile database for obfuscation. */
  public GuardDB(File[] inFile) throws java.io.IOException
  {
    inJar = new JarFile[inFile.length];
    for(int i = 0; i < inFile.length; i++)
      inJar[i] = new JarFile(inFile[i]);
  }

  /** Close input JAR file and log-file at GC-time. */
  protected void finalize() throws java.io.IOException
  {
    close();
  }

  public void setResourceHandler(ResourceHandler handler)
  {
    resourceHandler = handler;
  }

  public String getOutName(String inName)
  {
    return classTree.getOutName(inName);
  }

  /**
   * Go through database marking certain entities for retention, while
   * maintaining polymorphic integrity.
   */
  public void retain(Collection rgsEntries, PrintWriter log)throws java.io.IOException
  {

    // Build database if not already done, or if a mapping has already been generated
    if (classTree == null || hasMap)
    {
      hasMap = false;
      buildClassTree(log);
    }

    // look for obfuscation annotations that indicate retention
    retainByAnnotation();


    // Enumerate the entries in the RGS script
    retainByRule(rgsEntries, log);
  }

  private void retainByAnnotation() {
    classTree.walkTree(new TreeAction(){

      private ObfuscationConfig getApplyingObfuscationConfig(Cl cl){
        ObfuscationConfig obfuscationConfig = cl.getObfuscationConfig();
        if (cl.getObfuscationConfig() != null && obfuscationConfig.applyToMembers){
          return obfuscationConfig;
        }
        Cl currentCl = cl;
        // walk to the first class in the parent hierarchy that has applyToMembers set
        while (currentCl.isInnerClass()){
          TreeItem parent = currentCl.getParent();
          if (parent instanceof Cl){
            currentCl = (Cl) parent;
            ObfuscationConfig parentConfig = currentCl.getObfuscationConfig();
            if (parentConfig != null && parentConfig.applyToMembers) {
              return parentConfig;
            }
          } else {
            // if not a cl than stop
            return null;
          }
        }
        // we didn't find anything
        return null;
      }

      @Override
      public void classAction(Cl cl) {
        super.classAction(cl);

        // iterate over the annotations of the class to see if one specifies the obfuscation
        ObfuscationConfig config = cl.getObfuscationConfig();

        if (config != null) {
          if (config.exclude){
            classTree.retainClass(cl.getFullInName(), YGuardRule.LEVEL_PRIVATE, YGuardRule.LEVEL_NONE, YGuardRule.LEVEL_NONE, true);
          }
        } else {
          // no annotation, check parent hierarchy
          ObfuscationConfig parentConfig = getApplyingObfuscationConfig(cl);
          if (parentConfig != null && parentConfig.exclude){
            // a parent has annotation that applies his config to members which is: exclude
            classTree.retainClass(cl.getFullInName(), YGuardRule.LEVEL_PRIVATE, YGuardRule.LEVEL_NONE, YGuardRule.LEVEL_NONE, true);
          }
        }
      }

      @Override
      public void methodAction(Md md) {
        super.methodAction(md);
        // iterate over the annotations of the method to see if one specifies the obfuscation
        ObfuscationConfig config = md.getObfuscationConfig();

        // annotation at method overrides parent annotation
        if (config != null){
          if (config.exclude) {
            classTree.retainMethod(md.getFullInName(), md.getDescriptor());
          }
        } else {
          // no annotation, check parent hierarchy
          ObfuscationConfig parentConfig = getApplyingObfuscationConfig((Cl) md.getParent());
          if (parentConfig != null && parentConfig.exclude){
            // a parent has annotation that applies his config to members which is: exclude
            classTree.retainMethod(md.getFullInName(), md.getDescriptor());
          }
        }
      }

      @Override
      public void fieldAction(Fd fd) {
        super.fieldAction(fd);
        // iterate over the annotations of the field to see if one specifies the obfuscation
        ObfuscationConfig config = fd.getObfuscationConfig();

        // annotation at field overrides parent annotation
        if (config != null){
          if (config.exclude) {
            classTree.retainField(fd.getFullInName());
          }
        } else {
          // no annotation, check parent hierarchy
          ObfuscationConfig parentConfig = getApplyingObfuscationConfig((Cl) fd.getParent());
          if (parentConfig != null && parentConfig.exclude){
            // a parent has annotation that applies his config to members which is: exclude
            classTree.retainField(fd.getFullInName());
          }
        }
      }
    });
  }

  private void retainByRule(Collection rgsEntries, PrintWriter log) {
    for (Iterator it = rgsEntries.iterator(); it.hasNext();)
    {
      YGuardRule entry = (YGuardRule)it.next();
      try
      {
        switch (entry.type)
        {
          case YGuardRule.TYPE_LINE_NUMBER_MAPPER:
            classTree.retainLineNumberTable(entry.name,  entry.lineNumberTableMapper);
            break;
          case YGuardRule.TYPE_SOURCE_ATTRIBUTE_MAP:
            classTree.retainSourceFileAttributeMap(entry.name, entry.obfName);
            break;
          case YGuardRule.TYPE_ATTR:
            classTree.retainAttribute(entry.name);
            break;
          case YGuardRule.TYPE_ATTR2:
            classTree.retainAttributeForClass(entry.descriptor, entry.name);
            break;
          case YGuardRule.TYPE_CLASS:
            classTree.retainClass(entry.name, entry.retainClasses, entry.retainMethods, entry.retainFields, true);
            break;
          case YGuardRule.TYPE_METHOD:
            classTree.retainMethod(entry.name, entry.descriptor);
            break;
          case YGuardRule.TYPE_PACKAGE:
            classTree.retainPackage(entry.name);
            break;
          case YGuardRule.TYPE_FIELD:
            classTree.retainField(entry.name);
            break;
          case YGuardRule.TYPE_PACKAGE_MAP:
            classTree.retainPackageMap(entry.name, entry.obfName);
            break;
          case YGuardRule.TYPE_CLASS_MAP:
            classTree.retainClassMap(entry.name, entry.obfName);
            break;
          case YGuardRule.TYPE_METHOD_MAP:
            classTree.retainMethodMap(entry.name, entry.descriptor,
            entry.obfName);
            break;
          case YGuardRule.TYPE_FIELD_MAP:
            classTree.retainFieldMap(entry.name, entry.obfName);
            break;
          default:
            throw new ParseException("Illegal type: " + entry.type);
        }
      }
      catch (RuntimeException e)
      {
        // DEBUG
        // e.printStackTrace();
        log.println(WARNING_SCRIPT_ENTRY_ABSENT + entry.name + " -->");
      }
    }
  }

  /** Remap each class based on the remap database, and remove attributes. */
  public void remapTo(File[] out,
    Filter fileFilter,
    PrintWriter log,
    boolean conserveManifest
    ) throws java.io.IOException, ClassNotFoundException
  {
    // Build database if not already done
    if (classTree == null)
    {
      buildClassTree(log);
    }

    // Generate map table if not already done
    if (!hasMap)
    {
      createMap(log);
    }

    oldManifest = new Manifest[out.length];
    newManifest = new Manifest[out.length];
    parseManifest();

    StringBuffer replaceNameLog = new StringBuffer();
    StringBuffer replaceContentsLog = new StringBuffer();

    JarOutputStream outJar = null;
    // Open the entry and prepare to process it
    DataInputStream inStream = null;
    OutputStream os = null;
    for(int i = 0; i < inJar.length; i++)
    {
      os = null;
      outJar = null;
      //store the whole jar in memory, I known this might be alot, but anyway
      //this is the best option, if you want to create correct jar files...
      List jarEntries = new ArrayList();
      try
      {
        // Go through the input Jar, removing attributes and remapping the Constant Pool
        // for each class file. Other files are copied through unchanged, except for manifest
        // and any signature files - these are deleted and the manifest is regenerated.
        Enumeration entries = inJar[i].entries();
        fireObfuscatingJar(inJar[i].getName(), out[i].getName());
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        while (entries.hasMoreElements())
        {
          // Get the next entry from the input Jar
          JarEntry inEntry = (JarEntry)entries.nextElement();

          // Ignore directories
          if (inEntry.isDirectory())
          {
            continue;
          }

          inStream = new DataInputStream(
            new BufferedInputStream(
            inJar[i].getInputStream(inEntry)));
          String inName = inEntry.getName();
          if (inName.endsWith(CLASS_EXT))
          {
            if (fileFilter == null || fileFilter.accepts(inName)){
              // Write the obfuscated version of the class to the output Jar
              ClassFile cf = ClassFile.create(inStream);
              fireObfuscatingClass(Conversion.toJavaClass(cf.getName()));
              cf.remap(classTree, replaceClassNameStrings, log);
              JarEntry outEntry = new JarEntry(cf.getName() + CLASS_EXT);

              DataOutputStream classOutputStream;
              MessageDigest[] digests;
              if (digestStrings == null){
                digestStrings = new String[]{"SHA-1", "MD5"};
              }
              digests = new MessageDigest[digestStrings.length];
              OutputStream stream = baos;
              // Create an OutputStream piped through a number of digest generators for the manifest

              for (int j = 0; j < digestStrings.length; j++) {
                String digestString = digestStrings[j];
                MessageDigest digest = MessageDigest.getInstance(digestString);
                digests[j] = digest;
                stream = new DigestOutputStream(stream, digest);
              }
              classOutputStream = new DataOutputStream(stream);

              // Dump the classfile, while creating the digests
              cf.write(classOutputStream);
              classOutputStream.flush();
              jarEntries.add(new Object[]{outEntry, baos.toByteArray()});
              baos.reset();
              // Now update the manifest entry for the class with new name and new digests
              updateManifest(i, inName, cf.getName() + CLASS_EXT, digests);
            }
          }
          else if (STREAM_NAME_MANIFEST.equals(inName.toUpperCase()) ||
            (inName.length() > (SIGNATURE_PREFIX.length() + 1 + SIGNATURE_EXT.length()) &&
            inName.indexOf(SIGNATURE_PREFIX) != -1 &&
            inName.substring(inName.length() - SIGNATURE_EXT.length(), inName.length()).equals(SIGNATURE_EXT)))
          {
            // Don't pass through the manifest or signature files
            continue;
          }
          else
          {
            // Copy the non-class entry through unchanged
            long size = inEntry.getSize();
            if (size != -1)
            {

              // Create an OutputStream piped through a number of digest generators for the manifest
              MessageDigest shaDigest = MessageDigest.getInstance("SHA");
              MessageDigest md5Digest = MessageDigest.getInstance("MD5");
              DataOutputStream dataOutputStream =
              new DataOutputStream(new DigestOutputStream(new DigestOutputStream(baos,
              shaDigest),
              md5Digest));

              String outName;

              StringBuffer outNameBuffer = new StringBuffer(80);

              if(resourceHandler != null && resourceHandler.filterName(inName, outNameBuffer))
              {
                outName = outNameBuffer.toString();
                if(!outName.equals(inName))
                {
                  replaceNameLog.append("  <resource name=\"");
                  replaceNameLog.append(ClassTree.toUtf8XmlString(inName));
                  replaceNameLog.append("\" map=\"");
                  replaceNameLog.append(ClassTree.toUtf8XmlString(outName));
                  replaceNameLog.append("\"/>\n");
                }
              }
              else
              {
                outName = classTree.getOutName(inName);
              }

              if(resourceHandler == null || !resourceHandler.filterContent(inStream, dataOutputStream, inName))
              {
                byte[] bytes = new byte[(int)size];
                inStream.readFully(bytes);

                // outName = classTree.getOutName(inName);
                // Dump the data, while creating the digests
                dataOutputStream.write(bytes, 0, bytes.length);
              }
              else
              {
                replaceContentsLog.append("  <resource name=\"");
                replaceContentsLog.append(ClassTree.toUtf8XmlString(inName));
                replaceContentsLog.append("\"/>\n");
              }

              dataOutputStream.flush();
              JarEntry outEntry = new JarEntry(outName);


              jarEntries.add(new Object[]{outEntry, baos.toByteArray()});
              baos.reset();
              // Now update the manifest entry for the entry with new name and new digests
              MessageDigest[] digests =
              {shaDigest, md5Digest};
              updateManifest(i , inName, outName, digests);
            }
          }
        }

        os = new FileOutputStream(out[i]);
        if (conserveManifest){
          outJar = new JarOutputStream(new BufferedOutputStream(os),oldManifest[i]);
        } else {
          outJar = new JarOutputStream(new BufferedOutputStream(os),newManifest[i]);
        }
        outJar.setComment( Version.getJarComment());

        // sort the entries in ascending order
        Collections.sort(jarEntries, new Comparator(){
          public int compare(Object a, Object b){
                Object[] array1 = (Object[]) a;
                JarEntry entry1 = (JarEntry) array1[0];
                Object[] array2 = (Object[]) b;
                JarEntry entry2 = (JarEntry) array2[0];
                return entry1.getName().compareTo(entry2.getName());
          }
        });
        // Finally, write the big bunch of data
        Set directoriesWritten = new HashSet();
        for (int j = 0; j < jarEntries.size(); j++){
          Object[] array = (Object[]) jarEntries.get(j);
          JarEntry entry = (JarEntry) array[0];
          String name = entry.getName();
          // make sure the directory entries are written to the jar file
          if (!entry.isDirectory()){
                int index = 0;
                while ((index = name.indexOf("/", index + 1))>= 0){
                  String directory = name.substring(0, index+1);
                  if (!directoriesWritten.contains(directory)){
                        directoriesWritten.add(directory);
                        JarEntry directoryEntry = new JarEntry(directory);
                        outJar.putNextEntry(directoryEntry);
                        outJar.closeEntry();
                  }
                }
          }
          // write the entry itself
          byte[] bytes = (byte[]) array[1];
          outJar.putNextEntry(entry);
          outJar.write(bytes);
          outJar.closeEntry();
        }

      }
      catch (Exception e)
      {
        // Log exceptions before exiting
        log.println();
        log.println("<!-- An exception has occured.");
        if (e instanceof java.util.zip.ZipException){
          log.println("This is most likely due to a duplicate .class file in your jar!");
          log.println("Please check that there are no out-of-date or backup duplicate .class files in your jar!");
        }
        log.println(e.toString());
        e.printStackTrace(log);
        log.println("-->");
        throw new IOException("An error ('"+e.getMessage()+"') occured during the remapping! See the log!)");
      }
      finally
      {
        inJar[i].close();
        if (inStream != null)
        {
          inStream.close();
        }
        if (outJar != null)
        {
          outJar.close();
        }
        if (os != null){
          os.close();
        }
      }
    }
    // Write the mapping table to the log file
    classTree.dump(log);
    if(replaceContentsLog.length() > 0 || replaceNameLog.length() > 0)
    {
      log.println("<!--");
      if(replaceNameLog.length() > 0)
      {
        log.println("\n<adjust replaceName=\"true\">");
        log.print(replaceNameLog);
        log.println("</adjust>");
      }
      if(replaceContentsLog.length() > 0)
      {
        log.println("\n<adjust replaceContents=\"true\">");
        log.print(replaceContentsLog);
        log.println("</adjust>");
      }
      log.println("-->");
    }

  }

  /** Close input JAR file. */
  public void close() throws java.io.IOException
  {
    for(int i = 0; i < inJar.length; i++)
    {
      if (inJar[i] != null)
      {
        inJar[i].close();
        inJar[i] = null;
      }
    }
  }

  // Parse the RFC822-style MANIFEST.MF file
  private void parseManifest()throws java.io.IOException
  {
    for(int i = 0; i < oldManifest.length; i++)
    {
      // The manifest file is the first in the jar and is called
      // (case insensitively) 'MANIFEST.MF'
      oldManifest[i] = inJar[i].getManifest();

      if (oldManifest[i] == null){
        oldManifest[i] = new Manifest();
      }

      // Create a fresh manifest, with a version header
      newManifest[i] = new Manifest();

      // copy all main attributes
      for (Iterator it = oldManifest[i].getMainAttributes().entrySet().iterator(); it.hasNext();) {
        Map.Entry entry = (Map.Entry) it.next();
        Attributes.Name name = (Attributes.Name) entry.getKey();
        String value = (String) entry.getValue();
        if (resourceHandler != null) {
          name = new Attributes.Name(resourceHandler.filterString(name.toString(), "META-INF/MANIFEST.MF"));
          value = resourceHandler.filterString(value, "META-INF/MANIFEST.MF");
        }
        newManifest[i].getMainAttributes().putValue(name.toString(), value);
      }

      newManifest[i].getMainAttributes().putValue("Created-by", "yGuard Bytecode Obfuscator " + Version.getVersion());

      // copy all directory entries
      for (Iterator it = oldManifest[i].getEntries().entrySet().iterator();
            it.hasNext();){
         Map.Entry entry = (Map.Entry) it.next();
         String name = (String) entry.getKey();
         if (name.endsWith("/")){
           newManifest[i].getEntries().put(name, (Attributes) entry.getValue());
         }
      }
    }
  }

  // Update an entry in the manifest file
  private void updateManifest(int manifestIndex, String inName, String outName, MessageDigest[] digests)
  {
    // Create fresh section for entry, and enter "Name" header

    Manifest nm = newManifest[manifestIndex];
    Manifest om = oldManifest[manifestIndex];

    Attributes oldAtts = om.getAttributes(inName);
    Attributes newAtts = new Attributes();
    //newAtts.putValue(MANIFEST_NAME_TAG, outName);

    // copy over non-name and none digest entries
    if (oldAtts != null){
      for(Iterator it = oldAtts.entrySet().iterator(); it.hasNext();){
        Map.Entry entry = (Map.Entry) it.next();
        Object key = entry.getKey();
        String name = key.toString();
        if (!name.equalsIgnoreCase(MANIFEST_NAME_TAG) &&
            name.indexOf("Digest") == -1){
          newAtts.remove(name);
          newAtts.putValue(name, (String)entry.getValue());
        }
      }
    }

    // Create fresh digest entries in the new section
    if (digests != null && digests.length > 0)
    {
      // Digest-Algorithms header
      StringBuffer sb = new StringBuffer();
      for (int i = 0; i < digests.length; i++)
      {
        sb.append(digests[i].getAlgorithm());
        if (i < digests.length -1){
          sb.append(", ");
        }
      }
      newAtts.remove(MANIFEST_DIGESTALG_TAG);
      newAtts.putValue(MANIFEST_DIGESTALG_TAG, sb.toString());

      // *-Digest headers
      for (int i = 0; i < digests.length; i++)
      {
        newAtts.remove(digests[i].getAlgorithm() + "-Digest");
        newAtts.putValue(digests[i].getAlgorithm() + "-Digest", Tools.toBase64(digests[i].digest()));
      }
    }

    if (!newAtts.isEmpty()) {
      // Append the new section to the new manifest
      nm.getEntries().put(outName, newAtts);
    }
  }

  // Create a classfile database.
  private void buildClassTree(PrintWriter log)throws java.io.IOException
  {
    // Go through the input Jar, adding each class file to the database
    classTree = new ClassTree();
    classTree.setPedantic(isPedantic());
    classTree.setReplaceClassNameStrings(replaceClassNameStrings);
    ClassFile.resetDangerHeader();
    
    Map parsedClasses = new HashMap();
    for(int i = 0; i < inJar.length; i++)
    {
      Enumeration entries = inJar[i].entries();
      fireParsingJar(inJar[i].getName());
      while (entries.hasMoreElements())
      {
        // Get the next entry from the input Jar
        ZipEntry inEntry = (ZipEntry)entries.nextElement();
        String name = inEntry.getName();
        if (name.endsWith(CLASS_EXT))
        {
          fireParsingClass(Conversion.toJavaClass(name));
          // Create a full internal representation of the class file
          DataInputStream inStream = new DataInputStream(
          new BufferedInputStream(
          inJar[i].getInputStream(inEntry)));
          ClassFile cf = null;
          try
          {
            cf = ClassFile.create(inStream);
          }
          catch (Exception e)
          {
            log.println(ERROR_CORRUPT_CLASS + createJarName(inJar[i], name) + " -->");
            e.printStackTrace(log);
            throw new ParseException( e );
          }
          finally
          {
            inStream.close();
          }

          if (cf != null){
            final String cfn = cf.getName();
            final String key =
                    "module-info".equals(cfn) ? createModuleKey(cf) : cfn;

            Object[] old = (Object[]) parsedClasses.get(key);
            if (old != null){
              int jarIndex = ((Integer)old[0]).intValue();
              String warning = "yGuard detected a duplicate class definition " +
                "for \n    " + Conversion.toJavaClass(cfn) +
              "\n    [" + createJarName(inJar[jarIndex], old[1].toString()) + "] in \n    [" +
                createJarName(inJar[i], name) + "]";
              log.write("<!-- \n" + warning + "\n-->\n");
              if (jarIndex == i){
                throw new IOException(warning + "\nPlease remove inappropriate duplicates first!");
              } else {
                if (pedantic){
                  throw new IOException(warning + "\nMake sure these files are of the same version!");
                } 
              }
            } else {
              parsedClasses.put(key, new Object[]{new Integer(i), name});
            }

            // Check the classfile for references to 'dangerous' methods
            cf.logDangerousMethods(log, replaceClassNameStrings);
            classTree.addClassFile(cf);
          }

        }
      }
    }

    // set the java access modifiers from the containing class (muellese)
    final ClassTree ct = classTree;
    ct.walkTree(new TreeAction()
    {
      public void classAction(Cl cl)
      {
        if (cl.isInnerClass())
        {
          Cl parent = (Cl) cl.getParent();
          cl.access = parent.getInnerClassModifier(cl.getInName());
        }
      }
    });
  }

  private static String createJarName(JarFile jar, String name){
    return "jar:"+jar.getName() + "|" + name;
  }

  private static String createModuleKey( final ClassFile cf ) {
    return "module-info:" + cf.findModuleName();
  }

  // Generate a mapping table for obfuscation.
  private void createMap(PrintWriter log) throws ClassNotFoundException
  {
    // Traverse the class tree, generating obfuscated names within
    // package and class namespaces
    classTree.generateNames();

    // Resolve the polymorphic dependencies of each class, generating
    // non-private method and field names for each namespace
    classTree.resolveClasses();

    // Signal that the namespace maps have been created
    hasMap = true;

    // Write the memory usage at this point to the log file
    Runtime rt = Runtime.getRuntime();
    rt.gc();
    log.println("<!--");
    log.println(LOG_MEMORY_USED + Long.toString(rt.totalMemory() - rt.freeMemory()) + LOG_MEMORY_BYTES);
    log.println(LOG_MEMORY_TOTAL + Long.toString(rt.totalMemory()) + LOG_MEMORY_BYTES);
    log.println("-->");

  }

  protected void fireParsingJar(String jar){
    if (listenerList == null) return;
    for (int i = 0, j = listenerList.size(); i < j; i++){
      ((ObfuscationListener)listenerList.get(i)).parsingJar(jar);
    }
  }
  protected void fireParsingClass(String className){
    if (listenerList == null) return;
    for (int i = 0, j = listenerList.size(); i < j; i++){
      ((ObfuscationListener)listenerList.get(i)).parsingClass(className);
    }
  }
  protected void fireObfuscatingJar(String inJar, String outJar){
    if (listenerList == null) return;
    for (int i = 0, j = listenerList.size(); i < j; i++){
      ((ObfuscationListener)listenerList.get(i)).obfuscatingJar(inJar, outJar);
    }
  }
  protected void fireObfuscatingClass(String className){
    if (listenerList == null) return;
    for (int i = 0, j = listenerList.size(); i < j; i++){
      ((ObfuscationListener)listenerList.get(i)).obfuscatingClass(className);
    }
  }

  /** Registers Listener to receive events.
   * @param listener The listener to register.
   */
  public synchronized void addListener(com.yworks.yguard.ObfuscationListener listener)
  {
    if (listenerList == null )
    {
      listenerList = new java.util.ArrayList();
    }
    listenerList.add(listener);
  }

  /** Removes Listener from the list of listeners.
   * @param listener The listener to remove.
   */
  public synchronized void removeListener(com.yworks.yguard.ObfuscationListener listener)
  {
    if (listenerList != null )
    {
      listenerList.remove(listener);
    }
  }

  /** Getter for property replaceClassNameStrings.
   * @return Value of property replaceClassNameStrings.
   *
   */
  public boolean isReplaceClassNameStrings()
  {
    return this.replaceClassNameStrings;
  }

  /** Setter for property replaceClassNameStrings.
   * @param replaceClassNameStrings New value of property replaceClassNameStrings.
   *
   */
  public void setReplaceClassNameStrings(boolean replaceClassNameStrings)
  {
    this.replaceClassNameStrings = replaceClassNameStrings;
  }


  /** Getter for property pedantic.
   * @return Value of property pedantic.
   *
   */
  public boolean isPedantic()
  {
    return this.pedantic;
  }

  /** Setter for property pedantic.
   * @param pedantic New value of property pedantic.
   *
   */
  public void setPedantic(boolean pedantic)
  {
    this.pedantic = pedantic;
    Cl.setPedantic(pedantic);
  }


  /**
   * Returns the obfuscated file name of the java class.
   * The ending ".class" is omitted.
   * @param javaClass the fully qualified name of an unobfuscated class.
   */
  public String translateJavaFile(String javaClass)
  {
    Cl cl = classTree.findClassForName(javaClass.replace('/','.'));
    if(cl != null)
    {
      return cl.getFullOutName();
    }
    else
    {
      return javaClass;
    }
  }


  public String translateJavaClass(String javaClass)
  {
    Cl cl = classTree.findClassForName(javaClass);
    if(cl != null)
    {
      return cl.getFullOutName().replace('/', '.');
    }
    else
    {
      return javaClass;
    }
  }

  public void setDigests(String[] digestStrings) {
    this.digestStrings = digestStrings;
  }

  public void setAnnotationClass(String annotationClass) {
    ObfuscationConfig.annotationClassName = annotationClass;
  }
}

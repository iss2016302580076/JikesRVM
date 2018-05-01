/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.classloader;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jikesrvm.VM;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.util.ImmutableEntryHashMapRVM;
import org.jikesrvm.util.ImmutableEntryHashSetRVM;

/**
 * Implements an object that functions as the bootstrap class loader.
 * This class is a Singleton pattern.
 */
public final class BootstrapClassLoader extends java.lang.ClassLoader {

  private static final String REPLACEMENT_CLASS_NAME_PREFIX = "org.jikesrvm.classlibrary.openjdk.replacements.";

  private final ImmutableEntryHashMapRVM<String, RVMType> loaded =
    new ImmutableEntryHashMapRVM<String, RVMType>();

  private final ImmutableEntryHashSetRVM<Atom> loadedReplacementClasses = new ImmutableEntryHashSetRVM<Atom>();
  private final ImmutableEntryHashSetRVM<Atom> classesCheckedForReplacements = new ImmutableEntryHashSetRVM<Atom>();

  /** Places whence we load bootstrap .class files. */
  private static String bootstrapClasspath;

  /**
   * Set list of places to be searched for VM classes and resources.
   * @param bootstrapClasspath path specification in standard "classpath"
   *    format
   */
  public static void setBootstrapRepositories(String bootstrapClasspath) {
    BootstrapClassLoader.bootstrapClasspath = bootstrapClasspath;
  }

  /**
   * @return List of places to be searched for VM classes and resources,
   *      in standard "classpath" format
   */
  public static String getBootstrapRepositories() {
    return bootstrapClasspath;
  }

  /**
   * Initialize for execution.
   * @param bootstrapClasspath names of directories containing the bootstrap
   * .class files, and the names of any .zip/.jar files.
   * These are the ones that implement the VM and its
   * standard runtime libraries.  This may contain several names separated
   * with colons (':'), just
   * as a classpath may.   (<code>null</code> ==&gt; use the values specified by
   * {@link #setBootstrapRepositories} when the boot image was created.  This
   * feature is not actually used, but may be helpful in avoiding trouble.)
   */
  public static void boot(String bootstrapClasspath) {
    if (bootstrapClasspath != null) {
      BootstrapClassLoader.bootstrapClasspath = bootstrapClasspath;
    }
    zipFileCache = new HashMap<String, ZipFile>();
    if (VM.BuildForGnuClasspath && VM.runningVM) {
      try {
        // FIXME should be a classlibrary specific hook
        if (VM.BuildForGnuClasspath) {
          /* Here, we have to replace the fields that aren't carried over from
           * boot image writing time to run time.
           * This would be the following, if the fields weren't final:
           *
           * bootstrapClassLoader.definedPackages    = new HashMap();
           */
          Entrypoints.classLoaderDefinedPackages.setObjectValueUnchecked(bootstrapClassLoader,
                                                                            new java.util.HashMap<String, Package>());
        }
      } catch (Exception e) {
        VM.sysFail("Failed to setup bootstrap class loader");
      }
    }
  }

  /** Prevent other classes from constructing one. */
  private BootstrapClassLoader() {
    super(null);
  }

  /* Interface */
  private static final BootstrapClassLoader bootstrapClassLoader = new BootstrapClassLoader();

  public static BootstrapClassLoader getBootstrapClassLoader() {
    return bootstrapClassLoader;
  }

  /**
   * Backdoor for use by TypeReference.resolve when !VM.runningVM.
   * As of this writing, it is not used by any other classes.
   * @param className name of the class to be loaded
   * @return type for the loaded class
   * @throws NoClassDefFoundError when no definition of the class was found
   */
  synchronized RVMType loadVMClass(String className) throws NoClassDefFoundError {
    try {
      InputStream is = getResourceAsStream(className.replace('.', File.separatorChar) + ".class");
      if (is == null) throw new NoClassDefFoundError(className);
      DataInputStream dataInputStream = new DataInputStream(is);
      RVMType type = null;
      try {
        // Debugging:
        // VM.sysWriteln("loadVMClass: trying to resolve className " + className);
        type = RVMClassLoader.defineClassInternal(className, dataInputStream, this);
        loaded.put(className, type);
        if (VM.writingBootImage) attemptToLoadReplacementClassIfNeededForVmClass(className);
      } finally {
        try {
          // Make sure the input stream is closed.
          dataInputStream.close();
        } catch (IOException e) { }
      }
      return type;
    } catch (NoClassDefFoundError e) {
      throw e;
    } catch (Throwable e) {
      // We didn't find the class, or it wasn't valid, etc.
      NoClassDefFoundError ncdf = new NoClassDefFoundError(className);
      ncdf.initCause(e);
      throw ncdf;
    }
  }

  @Override
  public synchronized Class<?> loadClass(String className, boolean resolveClass) throws ClassNotFoundException {
    if (!VM.runningVM) {
      return super.loadClass(className, resolveClass);
    }
    if (className.startsWith("L") && className.endsWith(";")) {
      className = className.substring(1, className.length() - 2);
    }

    attemptToLoadReplacementClassIfNeeded(className);

    RVMType loadedType = loaded.get(className);
    Class<?> loadedClass;
    if (loadedType == null) {
      loadedClass = findClass(className);
    } else {
      loadedClass = loadedType.getClassForType();
    }
    if (resolveClass) {
      resolveClass(loadedClass);
    }
    return loadedClass;
  }

  // TODO move replacement class stuff to a separate class

  private void attemptToLoadReplacementClassIfNeeded(String className)
      throws NoClassDefFoundError, ClassNotFoundException {
    Atom classNameAtom = null;
    Atom replacementClassName = null;
    try {
      // TODO Adopt better nomenclature for replacement classes
      classNameAtom = Atom.findOrCreateAsciiAtom(className);
      Atom classNameDescriptor = Atom.findOrCreateAsciiAtom("L" + classNameAtom.toString().replace('.', '/') + ";");
      replacementClassName = toReplacementClassNameAtom(className);
      // TODO needs a method to convert class names to descriptors and vice versa. possibly in atom if it doesn't exist yet
      Atom replacementClassDescriptor = Atom.findOrCreateAsciiAtom("L" + replacementClassName.toString().replace('.', '/') + ";");

      boolean isReplacementClass = className.startsWith(REPLACEMENT_CLASS_NAME_PREFIX);

      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " is a replacement class itself and thus won't be checked for a replacement class: " + isReplacementClass);
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " is a bootstrap class descriptor? " + replacementClassDescriptor.isBootstrapClassDescriptor());
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " has been checked for replacement? " + classesCheckedForReplacements.contains(replacementClassName));
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " would have replacement class name: " + replacementClassName);
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " would have replacement class descriptor: " + replacementClassDescriptor);

      boolean alreadyTriedToLoadReplacementClass = classesCheckedForReplacements.contains(classNameAtom);
      // Check if there has been an attempt to load the replacement JDK class of this name
      if (!isReplacementClass && !alreadyTriedToLoadReplacementClass && classNameDescriptor.isBootstrapClassDescriptor()) {
        classesCheckedForReplacements.add(classNameAtom);
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Checking for replacement class for " + className + " named " + replacementClassName);

        // Load, resolve and instantiate the replacement class if it exists.
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: About to call findClass for replacement class for " + className + " named " + replacementClassName);
        Class<?> findClass = findClass(replacementClassName.toString());
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Finished with call findClass for replacement class for " + className + " named " + replacementClassName);
        RVMType replacementClassType = TypeReference.findOrCreate(findClass).resolve();
        replacementClassType.prepareForFirstUse();
        if (VM.VerifyAssertions) VM._assert(!VM.runningVM);

        loadedReplacementClasses.add(replacementClassName);

        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Replacement class " + replacementClassName + " loaded for " + className);
      }
    } catch (ClassNotFoundException ce) {
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: No replacement class for " + className);
      // FIXME check if this is still the case after porting is far enough along
      // don't print stack trace by default because it causes class loading issues right now
      final boolean printStackTrace = false;
      if (VM.TraceClassLoading && printStackTrace) ce.printStackTrace();
    } catch (NoSuchMethodError e) {
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Failed to load replacement class " + replacementClassName + " for " + classNameAtom);
        if (VM.TraceClassLoading) VM.sysWriteln(e.toString());
        if (VM.TraceClassLoading) e.printStackTrace();
        throw new ClassNotFoundException("Failed to load replacement class " + replacementClassName, e);
    }
  }

  private void attemptToLoadReplacementClassIfNeededForVmClass(String className)
      throws NoClassDefFoundError, ClassNotFoundException {
    Atom classNameAtom = null;
    Atom replacementClassName = null;
    try {
      // TODO Adopt better nomenclature for replacement classes
      classNameAtom = Atom.findOrCreateAsciiAtom(className);
      Atom classNameDescriptor = Atom.findOrCreateAsciiAtom("L" + classNameAtom.toString().replace('.', '/') + ";");
      replacementClassName = toReplacementClassNameAtom(className);
      // TODO needs a method to convert class names to descriptors and vice versa. possibly in atom if it doesn't exist yet
      Atom replacementClassDescriptor = Atom.findOrCreateAsciiAtom("L" + replacementClassName.toString().replace('.', '/') + ";");

      boolean isReplacementClass = className.startsWith(REPLACEMENT_CLASS_NAME_PREFIX);

      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " is a replacement class itself and thus won't be checked for a replacement class: " + isReplacementClass);
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " is a bootstrap class descriptor? " + replacementClassDescriptor.isBootstrapClassDescriptor());
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " has been checked for replacement? " + classesCheckedForReplacements.contains(replacementClassName));
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " would have replacement class name: " + replacementClassName);
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: " + className + " would have replacement class descriptor: " + replacementClassDescriptor);

      boolean alreadyTriedToLoadReplacementClass = classesCheckedForReplacements.contains(classNameAtom);
      // Check if there has been an attempt to load the replacement JDK class of this name
      if (!isReplacementClass && !alreadyTriedToLoadReplacementClass && classNameDescriptor.isBootstrapClassDescriptor()) {
        classesCheckedForReplacements.add(classNameAtom);
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Checking for replacement class for " + className + " named " + replacementClassName);

        if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
        // Load, resolve and instantiate the replacement class if it exists.
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: About to call loadVMClass for replacement class for " + className + " named " + replacementClassName);
        RVMType loadVMClass = loadVMClass(replacementClassName.toString());
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Finished with call loadVMClass for replacement class for " + className + " named " + replacementClassName);
        loadVMClass.prepareForFirstUse();
        loadedReplacementClasses.add(replacementClassName);

        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Replacement class " + replacementClassName + " loaded for " + className);
      }
    } catch (NoSuchMethodError e) {
        if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Failed to load replacement class " + replacementClassName + " for " + classNameAtom);
        if (VM.TraceClassLoading) VM.sysWriteln(e.toString());
        if (VM.TraceClassLoading) e.printStackTrace();
        throw new ClassNotFoundException("Failed to load replacement class " + replacementClassName, e);
    } catch (NoClassDefFoundError e) {
      // TODO we should have a lookup component for classes in jars that's usable indepedently of classloading and that just checks if a class were present if we
      // wanted to read it
      if (VM.TraceClassLoading) VM.sysWriteln("ClassReplacement: Failed to load replacement class " + replacementClassName + " for " + classNameAtom);
      if (VM.TraceClassLoading) VM.sysWriteln(e.toString());
      if (VM.TraceClassLoading) e.printStackTrace();
    }
  }

  public Atom toReplacementClassNameAtom(String className) {
    return Atom.findOrCreateAsciiAtom(REPLACEMENT_CLASS_NAME_PREFIX + className.replace('.', '_'));
  }

  /**
   * Search the bootstrap class loader's classpath for given class.
   *
   * @param className the name of the class to load
   * @return the class object, if it was found
   * @exception ClassNotFoundException if the class was not found, or was invalid
   */
  @Override
  public Class<?> findClass(String className) throws ClassNotFoundException {
    final boolean DBG = false;
    if (!VM.runningVM) {
      return super.findClass(className);
    }
    if (className.startsWith("[")) {
      TypeReference typeRef =
          TypeReference.findOrCreate(this, Atom.findOrCreateAsciiAtom(className.replace('.', '/')));
      RVMType ans = typeRef.resolve();
      loaded.put(className, ans);
      return ans.getClassForType();
    } else {
      if (!VM.fullyBooted) {
        VM.sysWrite("Trying to load a class (");
        VM.sysWrite(className);
        VM.sysWrite(") too early in the booting process, before dynamic");
        VM.sysWriteln(" class loading is enabled; aborting.");
        VM.sysFail("Trying to load a class too early in the booting process");
      }
      // class types: try to find the class file
      try {
        if (className.startsWith("L") && className.endsWith(";")) {
          className = className.substring(1, className.length() - 2);
        }
        InputStream is = getResourceAsStream(className.replace('.', File.separatorChar) + ".class");
        if (is == null) throw new ClassNotFoundException(className);
        DataInputStream dataInputStream = new DataInputStream(is);
        Class<?> cls = null;
        try {
          RVMType type = RVMClassLoader.defineClassInternal(className, dataInputStream, this);
          loaded.put(className, type);
          cls = type.getClassForType();
        } finally {
          try {
            // Make sure the input stream is closed.
            dataInputStream.close();
          } catch (IOException e) { }
        }
        return cls;
      } catch (ClassNotFoundException e) {
        throw e;
      } catch (Throwable e) {
        if (DBG) {
          VM.sysWrite("About to throw ClassNotFoundException(", className, ") because we got this Throwable:");
          e.printStackTrace();
        }
        // We didn't find the class, or it wasn't valid, etc.
        throw new ClassNotFoundException(className, e);
      }
    }
  }

  /** Keep this a static field, since it's looked at in
   *  {@link MemberReference#parse}. */
  public static final String myName = "BootstrapCL";

  @Override
  public String toString() {
    return myName;
  }

  private static HashMap<String, ZipFile> zipFileCache;

  private interface Handler<T> {
    void process(ZipFile zf, ZipEntry ze) throws Exception;

    void process(File f) throws Exception;

    T getResult();
  }

  @Override
  public InputStream getResourceAsStream(final String name) {
    Handler<InputStream> findStream = new Handler<InputStream>() {
      InputStream stream;

      @Override
      public InputStream getResult() {
        return stream;
      }

      @Override
      public void process(ZipFile zf, ZipEntry ze) throws Exception {
        stream = zf.getInputStream(ze);
      }

      @Override
      public void process(File file) throws Exception {
        stream = new FileInputStream(file);
      }
    };

    return getResourceInternal(name, findStream, false);
  }

  @Override
  public URL findResource(final String name) {
    Handler<URL> findURL = new Handler<URL>() {
      URL url;

      @Override
      public URL getResult() {
        return url;
      }

      @Override
      public void process(ZipFile zf, ZipEntry ze) throws Exception {
        url = new URL("jar", null, -1, "file:" + zf.getName() + "!/" + name);
      }

      @Override
      public void process(File file) throws Exception {
        url = new URL("file", null, -1, file.getName());
      }
    };

    return getResourceInternal(name, findURL, false);
  }

  @Override
  public Enumeration<URL> findResources(final String name) {
    Handler<Enumeration<URL>> findURL = new Handler<Enumeration<URL>>() {
      Vector<URL> urls;

      @Override
      public Enumeration<URL> getResult() {
        if (urls == null) urls = new Vector<URL>();
        return urls.elements();
      }

      @Override
      public void process(ZipFile zf, ZipEntry ze) throws Exception {
        if (urls == null) urls = new Vector<URL>();
        urls.add(new URL("jar", null, -1, "file:" + zf.getName() + "!/" + name));
      }

      @Override
      public void process(File file) throws Exception {
        if (urls == null) urls = new Vector<URL>();
        urls.add(new URL("file", null, -1, file.getName()));
      }
    };

    return getResourceInternal(name, findURL, true);
  }

  private <T> T getResourceInternal(String name, Handler<T> h, boolean multiple) {
    if (name.startsWith(File.separator)) {
      name = name.substring(File.separator.length());
    }

    StringTokenizer tok = new StringTokenizer(getBootstrapRepositories(), File.pathSeparator);

    while (tok.hasMoreElements()) {
      try {
        String path = tok.nextToken();
        if (path.endsWith(".jar") || path.endsWith(".zip")) {
          ZipFile zf = zipFileCache.get(path);
          if (zf == null) {
            zf = new ZipFile(path);
            zipFileCache.put(path, zf);
          }
          // Zip spec. states that separator must be '/' in the path
          if (File.separatorChar != '/') {
            name = name.replace(File.separatorChar, '/');
          }
          ZipEntry ze = zf.getEntry(name);
          if (ze == null) continue;

          h.process(zf, ze);
          if (!multiple) return h.getResult();
        } else if (path.endsWith(File.separator)) {
          File file = new File(path + name);
          if (file.exists()) {
            h.process(file);
            if (!multiple) return h.getResult();
          }
        } else {
          File file = new File(path + File.separator + name);
          if (file.exists()) {
            h.process(file);
            if (!multiple) return h.getResult();
          }
        }
      } catch (Exception e) {
        if (VM.TraceClassLoading) {
          VM.sysWriteln("Exception in getResourceInternal: ");
          e.printStackTrace();
        }
      }
    }

    return (multiple) ? h.getResult() : null;
  }
}

// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageIdentifier;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.TargetPatternEvaluator;
import com.google.devtools.build.lib.pkgcache.TransitivePackageLoader;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.SkyframePackageLoader;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.CyclesReporter;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skyframe-based package manager.
 *
 * <p>This is essentially a compatibility shim between the native Skyframe and non-Skyframe
 * parts of Blaze and should not be long-lived.
 */
class SkyframePackageManager implements PackageManager {

  private final SkyframePackageLoader packageLoader;
  private final SkyframeExecutor.SkyframeTransitivePackageLoader transitiveLoader;
  private final TargetPatternEvaluator patternEvaluator;
  private final AtomicReference<UnixGlob.FilesystemCalls> syscalls;
  private final AtomicReference<CyclesReporter> skyframeCyclesReporter;
  private final AtomicReference<PathPackageLocator> pkgLocator;
  private final AtomicInteger numPackagesLoaded;
  private final SkyframeExecutor skyframeExecutor;

  public SkyframePackageManager(SkyframePackageLoader packageLoader,
      SkyframeExecutor.SkyframeTransitivePackageLoader transitiveLoader,
      TargetPatternEvaluator patternEvaluator,
      AtomicReference<UnixGlob.FilesystemCalls> syscalls,
      AtomicReference<CyclesReporter> skyframeCyclesReporter,
      AtomicReference<PathPackageLocator> pkgLocator,
      AtomicInteger numPackagesLoaded,
      SkyframeExecutor skyframeExecutor) {
    this.packageLoader = packageLoader;
    this.transitiveLoader = transitiveLoader;
    this.patternEvaluator = patternEvaluator;
    this.skyframeCyclesReporter = skyframeCyclesReporter;
    this.pkgLocator = pkgLocator;
    this.syscalls = syscalls;
    this.numPackagesLoaded = numPackagesLoaded;
    this.skyframeExecutor = skyframeExecutor;
  }

  @Override
  public Package getLoadedPackage(PackageIdentifier pkgIdentifier) throws NoSuchPackageException {
    return packageLoader.getLoadedPackage(pkgIdentifier);
  }

  @ThreadSafe
  @Override
  public Package getPackage(EventHandler eventHandler, PackageIdentifier packageIdentifier)
      throws NoSuchPackageException, InterruptedException {
    try {
      return packageLoader.getPackage(eventHandler, packageIdentifier);
    } catch (NoSuchPackageException e) {
      if (e.getPackage() != null) {
        return e.getPackage();
      }
      throw e;
    }
  }

  @Override
  public Target getLoadedTarget(Label label) throws NoSuchPackageException, NoSuchTargetException {
    return getLoadedPackage(label.getPackageIdentifier()).getTarget(label.getName());
  }

  @Override
  public Target getTarget(EventHandler eventHandler, Label label)
      throws NoSuchPackageException, NoSuchTargetException, InterruptedException {
    return getPackage(eventHandler, label.getPackageIdentifier()).getTarget(label.getName());
  }

  @Override
  public boolean isTargetCurrent(Target target) {
    Package pkg = target.getPackage();
    try {
      return getLoadedPackage(target.getLabel().getPackageIdentifier()) == pkg;
    } catch (NoSuchPackageException e) {
      return false;
    }
  }

  @Override
  public void partiallyClear() {
    packageLoader.partiallyClear();
  }

  @Override
  public PackageManagerStatistics getStatistics() {
    return new PackageManagerStatistics() {
      @Override
      public int getPackagesLoaded() {
        return numPackagesLoaded.get();
      }

      @Override
      public int getPackagesLookedUp() {
        return -1;
      }

      @Override
      public int getCacheSize() {
        return -1;
      }
    };
  }

  @Override
  public boolean isPackage(EventHandler eventHandler, PackageIdentifier packageName) {
    return getBuildFileForPackage(packageName) != null;
  }

  @Override
  public void dump(PrintStream printStream) {
    skyframeExecutor.dumpPackages(printStream);
  }

  @ThreadSafe
  @Override
  public Path getBuildFileForPackage(PackageIdentifier packageName) {
    // Note that this method needs to be thread-safe, as it is currently used concurrently by
    // legacy blaze code.
    if (packageLoader.isPackageDeleted(packageName)) {
      return null;
    }
    // TODO(bazel-team): Use a PackageLookupValue here [skyframe-loading]
    // TODO(bazel-team): The implementation in PackageCache also checks for duplicate packages, see
    // BuildFileCache#getBuildFile [skyframe-loading]
    return pkgLocator.get().getPackageBuildFileNullable(packageName, syscalls);
  }

  @Override
  public PathPackageLocator getPackagePath() {
    return pkgLocator.get();
  }

  @Override
  public TransitivePackageLoader newTransitiveLoader() {
    return new SkyframeLabelVisitor(transitiveLoader, skyframeCyclesReporter);
  }

  @Override
  public TargetPatternEvaluator getTargetPatternEvaluator() {
    return patternEvaluator;
  }
}

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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.CollectionUtils;
import com.google.devtools.build.lib.concurrent.ThreadSafety;

/**
 * Factory for creating new {@link LinkerInput} objects.
 */
public abstract class LinkerInputs {
  /**
   * An opaque linker input that is not a library, for example a linker script or an individual
   * object file.
   */
  @ThreadSafety.Immutable
  public static class SimpleLinkerInput implements LinkerInput {
    private final Artifact artifact;

    public SimpleLinkerInput(Artifact artifact) {
      this.artifact = Preconditions.checkNotNull(artifact);
    }

    @Override
    public Artifact getArtifact() {
      return artifact;
    }

    @Override
    public Artifact getOriginalLibraryArtifact() {
      return artifact;
    }

    @Override
    public boolean containsObjectFiles() {
      return false;
    }

    @Override
    public boolean isFake() {
      return false;
    }

    @Override
    public Iterable<Artifact> getObjectFiles() {
      throw new IllegalStateException();
    }

    @Override
    public boolean equals(Object that) {
      if (this == that) {
        return true;
      }

      if (!(that instanceof SimpleLinkerInput)) {
        return false;
      }

      SimpleLinkerInput other = (SimpleLinkerInput) that;
      return artifact.equals(other.artifact) && isFake() == other.isFake();
    }

    @Override
    public int hashCode() {
      return artifact.hashCode();
    }

    @Override
    public String toString() {
      return "SimpleLinkerInput(" + artifact + ")";
    }
  }

  /**
   * A linker input that is a fake object file generated by cc_fake_binary. The contained
   * artifact must be an object file.
   */
  @ThreadSafety.Immutable
  private static class FakeLinkerInput extends SimpleLinkerInput {
    private FakeLinkerInput(Artifact artifact) {
      super(artifact);
      Preconditions.checkState(Link.OBJECT_FILETYPES.matches(artifact.getFilename()));
    }

    @Override
    public boolean isFake() {
      return true;
    }
  }

  /**
   * A library the user can link to. This is different from a simple linker input in that it also
   * has a library identifier.
   */
  public interface LibraryToLink extends LinkerInput {
    /**
     * Returns whether the library is a solib symlink.
     */
    boolean isSolibSymlink();
  }

  /**
   * This class represents a solib library symlink. Its library identifier is inherited from
   * the library that it links to.
   */
  @ThreadSafety.Immutable
  public static class SolibLibraryToLink implements LibraryToLink {
    private final Artifact solibSymlinkArtifact;
    private final Artifact libraryArtifact;

    private SolibLibraryToLink(Artifact solibSymlinkArtifact, Artifact libraryArtifact) {
      this.solibSymlinkArtifact = Preconditions.checkNotNull(solibSymlinkArtifact);
      this.libraryArtifact = libraryArtifact;
    }

    @Override
    public String toString() {
      return String.format("SolibLibraryToLink(%s -> %s",
          solibSymlinkArtifact.toString(), libraryArtifact.toString());
    }

    @Override
    public Artifact getArtifact() {
      return solibSymlinkArtifact;
    }

    @Override
    public boolean containsObjectFiles() {
      return false;
    }

    @Override
    public boolean isFake() {
      return false;
    }

    @Override
    public Iterable<Artifact> getObjectFiles() {
      throw new IllegalStateException(
          "LinkerInputs: does not support getObjectFiles: " + toString());
    }

    @Override
    public Artifact getOriginalLibraryArtifact() {
      return libraryArtifact;
    }

    @Override
    public boolean isSolibSymlink() {
      return true;
    }

    @Override
    public boolean equals(Object that) {
      if (this == that) {
        return true;
      }

      if (!(that instanceof SolibLibraryToLink)) {
        return false;
      }

      SolibLibraryToLink thatSolib = (SolibLibraryToLink) that;
      return
          solibSymlinkArtifact.equals(thatSolib.solibSymlinkArtifact) &&
          libraryArtifact.equals(thatSolib.libraryArtifact);
    }

    @Override
    public int hashCode() {
      return solibSymlinkArtifact.hashCode();
    }
  }

  /**
   * This class represents a library that may contain object files.
   */
  @ThreadSafety.Immutable
  private static class CompoundLibraryToLink implements LibraryToLink {
    private final Artifact libraryArtifact;
    private final Iterable<Artifact> objectFiles;

    private CompoundLibraryToLink(Artifact libraryArtifact, Iterable<Artifact> objectFiles) {
      this.libraryArtifact = Preconditions.checkNotNull(libraryArtifact);
      this.objectFiles = objectFiles == null ? null : CollectionUtils.makeImmutable(objectFiles);
    }

    @Override
    public String toString() {
      return String.format("CompoundLibraryToLink(%s)", libraryArtifact.toString());
    }

    @Override
    public Artifact getArtifact() {
      return libraryArtifact;
    }

    @Override
    public Artifact getOriginalLibraryArtifact() {
      return libraryArtifact;
    }

    @Override
    public boolean containsObjectFiles() {
      return objectFiles != null;
    }

    @Override
    public boolean isFake() {
      return false;
    }

    @Override
    public Iterable<Artifact> getObjectFiles() {
      Preconditions.checkNotNull(objectFiles);
      return objectFiles;
    }

    @Override
    public boolean equals(Object that) {
      if (this == that) {
        return true;
      }

      if (!(that instanceof CompoundLibraryToLink)) {
        return false;
      }

      return libraryArtifact.equals(((CompoundLibraryToLink) that).libraryArtifact);
    }

    @Override
    public int hashCode() {
      return libraryArtifact.hashCode();
    }

    @Override
    public boolean isSolibSymlink() {
      return false;
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  // Public factory constructors:
  //////////////////////////////////////////////////////////////////////////////////////

  /**
   * Creates linker input objects for non-library files.
   */
  public static Iterable<LinkerInput> simpleLinkerInputs(Iterable<Artifact> input) {
    return Iterables.transform(input, new Function<Artifact, LinkerInput>() {
        @Override
        public LinkerInput apply(Artifact artifact) {
          return simpleLinkerInput(artifact);
        }
      });
  }

  /**
   * Creates a linker input for which we do not know what objects files it consists of.
   */
  public static LinkerInput simpleLinkerInput(Artifact artifact) {
    // This precondition check was in place and *most* of the tests passed with them; the only
    // exception is when you mention a generated .a file in the srcs of a cc_* rule.
    // Preconditions.checkArgument(!ARCHIVE_LIBRARY_FILETYPES.contains(artifact.getFileType()));
    return new SimpleLinkerInput(artifact);
  }

  /**
   * Creates a fake linker input. The artifact must be an object file.
   */
  public static LinkerInput fakeLinkerInput(Artifact artifact) {
    return new FakeLinkerInput(artifact);
  }

  /**
   * Creates input libraries for which we do not know what objects files it consists of.
   */
  public static Iterable<LibraryToLink> opaqueLibrariesToLink(Iterable<Artifact> input) {
    return Iterables.transform(input, new Function<Artifact, LibraryToLink>() {
      @Override
      public LibraryToLink apply(Artifact artifact) {
        return opaqueLibraryToLink(artifact);
      }
    });
  }

  /**
   * Creates a solib library symlink from the given artifact.
   */
  public static LibraryToLink solibLibraryToLink(Artifact solibSymlink, Artifact original) {
    return new SolibLibraryToLink(solibSymlink, original);
  }

  /**
   * Creates an input library for which we do not know what objects files it consists of.
   */
  public static LibraryToLink opaqueLibraryToLink(Artifact artifact) {
    // This precondition check was in place and *most* of the tests passed with them; the only
    // exception is when you mention a generated .a file in the srcs of a cc_* rule.
    // It was very useful for proving that this actually works, though.
    // Preconditions.checkArgument(
    //     !(artifact.getGeneratingAction() instanceof CppLinkAction) ||
    //     !Link.ARCHIVE_LIBRARY_FILETYPES.contains(artifact.getFileType()));
    return new CompoundLibraryToLink(artifact, null);
  }

  /**
   * Creates a library to link with the specified object files.
   */
  public static LibraryToLink newInputLibrary(Artifact library, Iterable<Artifact> objectFiles) {
    return new CompoundLibraryToLink(library, objectFiles);
  }

  private static final Function<LibraryToLink, Artifact> LIBRARY_TO_NON_SOLIB =
      new Function<LibraryToLink, Artifact>() {
        @Override
        public Artifact apply(LibraryToLink input) {
          return input.getOriginalLibraryArtifact();
        }
      };

  public static Iterable<Artifact> toNonSolibArtifacts(Iterable<LibraryToLink> libraries) {
    return Iterables.transform(libraries, LIBRARY_TO_NON_SOLIB);
  }

  /**
   * Returns the linker input artifacts from a collection of {@link LinkerInput} objects.
   */
  public static Iterable<Artifact> toLibraryArtifacts(Iterable<? extends LinkerInput> artifacts) {
    return Iterables.transform(artifacts, new Function<LinkerInput, Artifact>() {
      @Override
      public Artifact apply(LinkerInput input) {
        return input.getArtifact();
      }
    });
  }
}

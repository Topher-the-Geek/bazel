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

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * A command-line implementation that wraps another command line and puts the arguments in a
 * parameter file if necessary
 *
 * <p>The Linux kernel has a limit for the command line length, and that can be easily reached
 * if, for example, a command is listing all its inputs on the command line.
 */
@Immutable
public final class ParamFileHelper {

  /**
   * Returns a params file artifact or null for a given command description.
   *
   *  <p>Returns null if parameter files are not to be used according to paramFileInfo, or if the
   * command line is short enough that a parameter file is not needed.
   *
   * <p>Make sure to add the returned artifact (if not null) as an input of the corresponding
   * action.
   *
   * @param executableArgs leading arguments that should never be wrapped in a parameter file
   * @param arguments arguments to the command (in addition to executableArgs), OR
   * @param commandLine a {@link CommandLine} that provides the arguments (in addition to
   *        executableArgs)
   * @param paramFileInfo parameter file information
   * @param configuration the configuration
   * @param analysisEnvironment the analysis environment
   * @param outputs outputs of the action (used to construct a filename for the params file)
   */
  public static Artifact getParamsFileMaybe(
      List<String> executableArgs,
      @Nullable Iterable<String> arguments,
      @Nullable CommandLine commandLine,
      @Nullable ParamFileInfo paramFileInfo,
      BuildConfiguration configuration,
      AnalysisEnvironment analysisEnvironment,
      Iterable<Artifact> outputs) {
    if (paramFileInfo == null ||
        getParamFileSize(executableArgs, arguments, commandLine)
            < configuration.getMinParamFileSize()) {
      return null;
    }

    return getParamsFile(analysisEnvironment, configuration, Iterables.getFirst(outputs, null));
  }

  /**
   * Returns a params file for the specified output file.
   */
  public static Artifact getParamsFile(AnalysisEnvironment analysisEnvironment,
      BuildConfiguration configuration, Artifact output) {
    PathFragment paramFilePath = ParameterFile.derivePath(output.getRootRelativePath());
    return analysisEnvironment.getDerivedArtifact(paramFilePath, configuration.getBinDirectory());
  }

  /**
   * Creates a command line using an external params file.
   *
   * <p>Call this with the result of {@link #getParamsFileMaybe} if it is not null.
   *
   * @param executableArgs leading arguments that should never be wrapped in a parameter file
   * @param arguments arguments to the command (in addition to executableArgs), OR
   * @param commandLine a {@link CommandLine} that provides the arguments (in addition to
   *        executableArgs)
   * @param isShellCommand true if this is a shell command
   * @param owner owner of the action
   * @param paramFileInfo parameter file information
   */
  public static CommandLine createWithParamsFile(
      List<String> executableArgs,
      @Nullable Iterable<String> arguments,
      @Nullable CommandLine commandLine,
      boolean isShellCommand,
      ActionOwner owner,
      List<Action> requiredActions,
      ParamFileInfo paramFileInfo,
      Artifact parameterFile) {
    Preconditions.checkNotNull(parameterFile);
    if (commandLine != null && arguments != null && !Iterables.isEmpty(arguments)) {
      throw new IllegalStateException("must provide either commandLine or arguments: " + arguments);
    }

    CommandLine paramFileContents =
        (commandLine != null) ? commandLine : CommandLine.ofInternal(arguments, false);
    requiredActions.add(new ParameterFileWriteAction(owner, parameterFile, paramFileContents,
        paramFileInfo.getFileType(), paramFileInfo.getCharset()));

    String pathWithFlag = paramFileInfo.getFlag() + parameterFile.getExecPathString();
    Iterable<String> commandArgv = Iterables.concat(executableArgs, ImmutableList.of(pathWithFlag));
    return CommandLine.ofInternal(commandArgv, isShellCommand);
  }

  /**
   * Creates a command line without using a params file.
   *
   * <p>Call this if {@link #getParamsFileMaybe} returns null.
   *
   * @param executableArgs leading arguments that should never be wrapped in a parameter file
   * @param arguments arguments to the command (in addition to executableArgs), OR
   * @param commandLine a {@link CommandLine} that provides the arguments (in addition to
   *        executableArgs)
   * @param isShellCommand true if this is a shell command
   */
  public static CommandLine createWithoutParamsFile(List<String> executableArgs,
      Iterable<String> arguments, CommandLine commandLine, boolean isShellCommand) {
    if (commandLine == null) {
      Iterable<String> commandArgv = Iterables.concat(executableArgs, arguments);
      return CommandLine.ofInternal(commandArgv, isShellCommand);
    }

    if (executableArgs.isEmpty()) {
      return commandLine;
    }

    return CommandLine.ofMixed(ImmutableList.copyOf(executableArgs), commandLine, isShellCommand);
  }

  /**
   * Estimates the params file size for the given arguments.
   */
  private static int getParamFileSize(
      List<String> executableArgs, Iterable<String> arguments, CommandLine commandLine) {
    Iterable<String> actualArguments = (commandLine != null) ? commandLine.arguments() : arguments;
    return getParamFileSize(executableArgs) + getParamFileSize(actualArguments);
  }

  private static int getParamFileSize(Iterable<String> args) {
    int size = 0;
    for (String s : args) {
      size += s.length() + 1;
    }
    return size;
  }
}

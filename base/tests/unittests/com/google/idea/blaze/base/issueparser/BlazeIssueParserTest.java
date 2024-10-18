/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.issueparser;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.MockBlazeProjectDataBuilder;
import com.google.idea.blaze.base.model.MockBlazeProjectDataManager;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectView;
import com.google.idea.blaze.base.projectview.ProjectViewManager;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.projectview.section.ListSection;
import com.google.idea.blaze.base.projectview.section.sections.TargetSection;
import com.google.idea.blaze.base.run.filter.FileResolver;
import com.google.idea.blaze.base.run.filter.StandardFileResolver;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.common.experiments.ExperimentService;
import com.google.idea.common.experiments.MockExperimentService;
import com.intellij.build.events.MessageEvent.Kind;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileManagerListener;
import com.intellij.openapi.vfs.impl.VirtualFileManagerImpl;
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl;
import com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BlazeIssueParser}. */
@RunWith(JUnit4.class)
public class BlazeIssueParserTest extends BlazeTestCase {
  private ImmutableList<BlazeIssueParser.Parser> parsers;
  private ImmutableList<BlazeIssueParser.Parser> targetDetectionParsers;

  @Override
  protected void initTest(Container applicationServices, Container projectServices) {
    super.initTest(applicationServices, projectServices);

    WorkspaceRoot workspaceRoot = new WorkspaceRoot(new File("/root"));
    applicationServices.register(ExperimentService.class, new MockExperimentService());
    BlazeProjectData blazeProjectData = MockBlazeProjectDataBuilder.builder(workspaceRoot).build();
    projectServices.register(
        BlazeProjectDataManager.class, new MockBlazeProjectDataManager(blazeProjectData));
    registerExtensionPoint(FileResolver.EP_NAME, FileResolver.class)
        .registerExtension(new StandardFileResolver());

    registerExtensionPointByName("com.intellij.virtualFileManagerListener", VirtualFileManagerListener.class);
    applicationServices.register(VirtualFileManager.class, new VirtualFileManagerImpl(List.of(new CoreLocalFileSystem())));

    ProjectViewManager projectViewManager = mock(ProjectViewManager.class);
    projectServices.register(ProjectViewManager.class, projectViewManager);

    ProjectViewSet projectViewSet =
        ProjectViewSet.builder()
            .add(
                new File(".blazeproject"),
                ProjectView.builder()
                    .add(
                        ListSection.builder(TargetSection.KEY)
                            .add(
                                TargetExpression.fromStringSafe(
                                    "//tests/com/google/a/b/c/d/baz:baz"))
                            .add(TargetExpression.fromStringSafe("//package/path:hello4")))
                    .build())
            .build();
    when(projectViewManager.getProjectViewSet()).thenReturn(projectViewSet);

    parsers =
        ImmutableList.of(
            new BlazeIssueParser.PythonCompileParser(project),
            new BlazeIssueParser.DefaultCompileParser(project),
            new BlazeIssueParser.TracebackParser(),
            new BlazeIssueParser.BuildParser(),
            new BlazeIssueParser.SkylarkErrorParser(),
            new BlazeIssueParser.LinelessBuildParser(),
            new BlazeIssueParser.ProjectViewLabelParser(projectViewSet),
            new BlazeIssueParser.InvalidTargetProjectViewPackageParser(
                projectViewSet, "no such package '(.*)': BUILD file not found on package path"),
            new BlazeIssueParser.InvalidTargetProjectViewPackageParser(
                projectViewSet, "no targets found beneath '(.*)'"),
            new BlazeIssueParser.InvalidTargetProjectViewPackageParser(
                projectViewSet, "ERROR: invalid target format '(.*)'"),
            new BlazeIssueParser.FileNotFoundBuildParser(workspaceRoot),
            BlazeIssueParser.GenericErrorParser.FOR_SYNC);

    targetDetectionParsers = BlazeIssueParser.targetDetectionQueryParsers(project, workspaceRoot);
  }


  @Test
  public void testParseTargetError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: invalid target format "
                + "'//javatests/com/google/devtools/aswb/testapps/aswbtestlib/...:alls': "
                + "invalid package name "
                + "'javatests/com/google/devtools/aswb/testapps/aswbtestlib/...': "
                + "package name component contains only '.' characters.");
    assertThat(issue).isNotNull();
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseCompileErrorWithAbsolutePath() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "/absolute/location/google3/java/com/google/android/SomeKotlin.kt:17: error: "
                + "non-static variable this cannot be referenced from a static context");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage())
        .isEqualTo("non-static variable this cannot be referenced from a static context");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseCompileError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "java/com/google/android/samples/helloroot/math/DivideMath.java:17: error: "
                + "non-static variable this cannot be referenced from a static context");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage())
        .isEqualTo("non-static variable this cannot be referenced from a static context");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseCompileErrorWithColumn() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "java/com/google/devtools/aswb/pluginrepo/googleplex/PluginsEndpoint.java:33:26: "
                + "error: '|' is not preceded with whitespace.");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("'|' is not preceded with whitespace.");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseCompileFatalErrorWithColumn() {
    // Clang also has a 'fatal error' category.
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "net/something/foo_bar.cc:29:10: fatal error: 'util/ptr_util2.h' file not found");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("'util/ptr_util2.h' file not found");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseCompileNoteWithColumn() {
    // Clang also has a 'note' category.
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "net/something/foo_bar.cc:30:11: note: in instantiation of member function "
                + "foo<bar>::baz() requested here ...");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage())
        .isEqualTo("in instantiation of member function foo<bar>::baz() requested here ...");
    assertThat(issue.getKind()).isEqualTo(Kind.INFO);
  }

  @Test
  public void testParseBuildError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: /root/javatests/package_path/BUILD:42:12: "
                + "Target '//java/package_path:helloroot_visibility' failed");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage())
        .isEqualTo("Target '//java/package_path:helloroot_visibility' failed");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseBuildBazelError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: /root/javatests/package_path/BUILD.bazel:42:12: "
                + "Target '//java/package_path:helloroot_visibility' failed");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage())
        .isEqualTo("Target '//java/package_path:helloroot_visibility' failed");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseSkylarkError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: /root/third_party/bazel/tools/ide/intellij_info_impl.bzl:42:12: "
                + "Variable artifact_location is read only");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("Variable artifact_location is read only");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseLinelessBuildError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: /path/to/root/java/package_path/BUILD:char offsets 1222--1229: "
                + "name 'grubber' is not defined");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("name 'grubber' is not defined");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseFileNotFoundError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: Extension file not found. Unable to load file '//third_party/bazel:tools/ide/"
                + "intellij_info.bzl': file doesn't exist or isn't a file");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("file doesn't exist or isn't a file");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseFileNotFoundErrorWithPackage() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: error loading package 'path/to/package': Extension file not found. Unable to"
                + " load file '//third_party/bazel:tools/ide/intellij_info.bzl': file doesn't exist"
                + " or isn't a file");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("file doesn't exist or isn't a file");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testLabelProjectViewParser() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "no such target '//package/path:hello4': "
                + "target 'hello4' not declared in package 'package/path' "
                + "defined by /path/to/root/package/path/BUILD");
    assertThat(issue).isNotNull();
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testPackageProjectViewParser() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "no such package 'package/path': BUILD file not found on package path");
    assertThat(issue).isNotNull();
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testDeletedBUILDFileButLeftPackageInLocalTargets() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "Error:com.google.a.b.Exception exception in Bar: no targets found beneath "
                + "'tests/com/google/a/b/c/d/baz' Thrown during call: ...");
    assertThat(issue).isNotNull();
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
    assertThat(issue.getMessage())
        .isEqualTo("no targets found beneath 'tests/com/google/a/b/c/d/baz'");
  }

  @Test
  public void testMultilineTraceback() {
    String[] lines =
        new String[] {
          "ERROR: /home/plumpy/whatever:9:12: Traceback (most recent call last):",
          "\tFile \"/path/to/root/java/com/google/android/samples/helloroot/BUILD\", line 8",
          "\t\tpackage_group(name = BAD_FUNCTION(\"hellogoogle...\"), ...\"])",
          "\tFile \"/path/to/root/java/com/google/android/samples/helloroot/BUILD\", line 9, "
              + "in package_group",
          "\t\tBAD_FUNCTION",
          "name 'BAD_FUNCTION' is not defined."
        };

    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    for (int i = 0; i < lines.length - 1; ++i) {
      IssueOutput issue = blazeIssueParser.parseIssue(lines[i]);
      assertThat(issue).isNull();
    }

    IssueOutput issue = blazeIssueParser.parseIssue(lines[lines.length - 1]);
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage().split("\n")).hasLength(lines.length);
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testLineAfterTracebackIsAlsoParsed() {
    String[] lines =
        new String[] {
          "ERROR: /home/plumpy/whatever:9:12: Traceback (most recent call last):",
          "\tFile \"/path/to/root/java/com/google/android/samples/helloroot/BUILD\", line 8",
          "\t\tpackage_group(name = BAD_FUNCTION(\"hellogoogle...\"), ...\"])",
          "\tFile \"/path/to/root/java/com/google/android/samples/helloroot/BUILD\", line 9, "
              + "in package_group",
          "\t\tBAD_FUNCTION",
          "name 'BAD_FUNCTION' is not defined."
        };

    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    for (int i = 0; i < lines.length; ++i) {
      blazeIssueParser.parseIssue(lines[i]);
    }

    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: /home/plumpy/whatever:char offsets 1222--1229: name 'grubber' is not defined");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("name 'grubber' is not defined");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testMultipleIssues() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "ERROR: /home/plumpy/whatever:char offsets 1222--1229: name 'grubber' is not defined");
    assertThat(issue).isNotNull();
    issue =
        blazeIssueParser.parseIssue(
            "ERROR: /home/plumpy/whatever:char offsets 1222--1229: name 'grubber' is not defined");
    assertThat(issue).isNotNull();
    issue =
        blazeIssueParser.parseIssue(
            "ERROR: /home/plumpy/whatever:char offsets 1222--1229: name 'grubber' is not defined");
    assertThat(issue).isNotNull();
  }

  @Test
  public void testExtraParserMatch() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(ImmutableList.of(new TestParser()));
    IssueOutput issue =
        blazeIssueParser.parseIssue("TEST This is a test message for our test parser.");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("This is a test message for our test parser.");
    assertThat(issue.getKind()).isEqualTo(Kind.WARNING);
  }

  @Test
  public void testParseGenericError() {
    for (var parsersList : Arrays.asList(parsers, targetDetectionParsers)) {
      BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsersList);
      String msg =
          "Bad target pattern 'USE_CANARY_BLAZE=1': package names may contain only "
              + "A-Z, a-z, 0-9, '/', '-', '.', ' ', '$', '(', ')' and '_'.";
      IssueOutput issue = blazeIssueParser.parseIssue("ERROR: " + msg);
      assertThat(issue).isNotNull();
      assertThat(issue.getMessage()).isEqualTo(msg);
      assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
    }
  }

  @Test
  public void testIgnoreExitCodeError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue = blazeIssueParser.parseIssue("ERROR: //foo/bar:unit_tests: Exit 1.");
    assertThat(issue).isNull();
  }

  @Test
  public void testIgnoreRedundantBuildError() {
    String[] lines =
        new String[] {
          "ERROR: /foo/bar/BUILD:1:1: Couldn't build file foo/bar/Foo-class.jar: Building"
              + " foo/bar/Foo-class.jar (1 source file) failed (Exit 1) java failed: error"
              + " executing command third_party/java/jdk/jdk11-k8/bin/java -Xms3072m -Xmx3072m"
              + " '-XX:MaxGCPauseMillis=20000' -XX:+IgnoreUnrecognizedVMOptions"
              + " -XX:-DeallocateHeapPages -XX:-GoogleAdjustGCThreads -XX:-GoogleG1ConcGCThreads"
              + " ... (remaining 24 argument(s) skipped).  [forge_remote_host=ikkl10]",
          "foo/bar/Foo.java:13: error: <identifier> expected", // 1
          "  asdf",
          "      ^",
          "foo/bar/Foo.java:13: error: cannot find symbol", // 4
          "  asdf",
          "  ^",
          "  symbol:   class asdf",
          "  location: class Foo",
        };
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    for (int i = 0; i < lines.length; ++i) {
      if (i == 1 || i == 4) {
        continue;
      }
      assertThat(blazeIssueParser.parseIssue(lines[i])).isNull();
    }
    assertThat(blazeIssueParser.parseIssue(lines[1])).isNotNull();
    assertThat(blazeIssueParser.parseIssue(lines[4])).isNotNull();
  }

  @Test
  public void testIgnoreInvalidTargetErrorsInTargetDetection() {
    var lines = """
        ERROR: Skipping '//foo/...:all': no targets found beneath 'run_configurations'
        ERROR: Skipping '//foo:foo.iml': no such target '//foo:foo.iml': target 'foo.iml' not declared in package 'foo' defined by /foo/BUILD; however, a source file of this name exists.  (Perhaps add 'exports_files(["foo.iml"])' to /foo/BUILD?)
        """.lines().toList();
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(targetDetectionParsers);
    for (String line : lines) {
      assertThat(blazeIssueParser.parseIssue(line)).isNull();
    }
  }

  @Test
  public void testParseTypeScriptCompileError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "foo/bar/baz.ts:123:45 - error TS2304: Cannot find name 'asdf'.");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("TS2304: Cannot find name 'asdf'.");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseJavaScriptCompileError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "foo/bar.js:10: ERROR - [JSC_UNDEFINED_VARIABLE] variable foo is undeclared");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("[JSC_UNDEFINED_VARIABLE] variable foo is undeclared");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParseInfo() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "abc/SomeClass.kt:117:11: info: 'when' expression on sealed"
                + " classes is recommended to be exhaustive");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage())
        .isEqualTo("'when' expression on sealed classes is recommended to be exhaustive");
    assertThat(issue.getKind()).isEqualTo(Kind.INFO);
  }

  @Test
  public void testParseGoCompileError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue = blazeIssueParser.parseIssue("foo/bar.go:123:45: undefined: asdf");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("undefined: asdf");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  @Test
  public void testParsePythonCompileError() {
    BlazeIssueParser blazeIssueParser = new BlazeIssueParser(parsers);
    IssueOutput issue =
        blazeIssueParser.parseIssue(
            "File \"foo/bar.py\", line 123, in foo: bad option in return type [bad-return-type]");
    assertThat(issue).isNotNull();
    assertThat(issue.getMessage()).isEqualTo("in foo: bad option in return type [bad-return-type]");
    assertThat(issue.getKind()).isEqualTo(Kind.ERROR);
  }

  /** Simple Parser for testing */
  private static class TestParser extends BlazeIssueParser.SingleLineParser {

    public TestParser() {
      super("^TEST (.*)$");
    }

    @Override
    protected IssueOutput createIssue(Matcher matcher) {
      return IssueOutput.warn(matcher.group(1)).build();
    }
  }
}

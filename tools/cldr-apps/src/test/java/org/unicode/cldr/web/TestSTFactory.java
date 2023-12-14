package org.unicode.cldr.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.ibm.icu.dev.util.ElapsedTimer;

import net.jcip.annotations.NotThreadSafe;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicode.cldr.draft.FileUtilities;
import org.unicode.cldr.test.CheckCLDR;
import org.unicode.cldr.unittest.web.TestAll;
import org.unicode.cldr.unittest.web.TestAll.WebTestInfo;
import org.unicode.cldr.util.CLDRConfig;
import org.unicode.cldr.util.CLDRConfig.Environment;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.CLDRFile.DraftStatus;
import org.unicode.cldr.util.CLDRLocale;
import org.unicode.cldr.util.CLDRPaths;
import org.unicode.cldr.util.CldrUtility;
import org.unicode.cldr.util.FileReaders;
import org.unicode.cldr.util.Organization;
import org.unicode.cldr.util.SpecialLocales;
import org.unicode.cldr.util.StackTracker;
import org.unicode.cldr.util.VoteResolver;
import org.unicode.cldr.util.VoteResolver.Level;
import org.unicode.cldr.util.VoteResolver.Status;
import org.unicode.cldr.util.XMLFileReader;
import org.unicode.cldr.util.XPathParts;
import org.unicode.cldr.web.BallotBox.InvalidXPathException;
import org.unicode.cldr.web.BallotBox.VoteNotAcceptedException;
import org.unicode.cldr.web.UserRegistry.LogoutException;
import org.unicode.cldr.web.UserRegistry.User;
import org.unicode.cldr.web.api.VoteAPI;
import org.unicode.cldr.web.api.VoteAPI.RowResponse;
import org.unicode.cldr.web.api.VoteAPI.RowResponse.Row;
import org.unicode.cldr.web.api.VoteAPIHelper;
import org.unicode.cldr.web.api.VoteAPIHelper.ArgsForGet;

@NotThreadSafe
public class TestSTFactory {

    TestAll.WebTestInfo testInfo = WebTestInfo.getInstance();

    STFactory gFac = null;
    UserRegistry.User gUser = null;

    /** validate the phase and mode */
    @BeforeEach
    public void validatePhase() {
        CLDRConfig c = CLDRConfig.getInstance();
        assertNotNull(c);
        assertTrue(
                c.getPhase().isUnitTest(),
                () -> String.format("Phase %s returned false for isUnitTest()", c.getPhase()));
        assertEquals(
                Environment.UNITTEST, c.getEnvironment(), "Please set -DCLDR_ENVIRONMENT=UNITTEST");
        TestAll.assumeHaveDb();
    }

    @Test
    public void TestBasicFactory() throws SQLException {
        CLDRLocale locale = CLDRLocale.getInstance("aa");
        STFactory fac = getFactory();
        CLDRFile mt = fac.make(locale, false);
        BallotBox<User> box = fac.ballotBoxForLocale(locale);
        mt.iterator();
        final String somePath = "//ldml/localeDisplayNames/keys/key[@type=\"collation\"]";
        box.getValues(somePath);
    }

    @Test
    public void TestReadonlyLocales() throws SQLException {
        STFactory fac = getFactory();

        verifyReadOnly(fac.make("root", false));
        verifyReadOnly(fac.make("en", false));
    }

    private static final String ANY = "*";
    private static final String NULL = "<NULL>";

    private String expect(
            String path,
            String expectString,
            boolean expectVoted,
            CLDRFile file,
            BallotBox<User> box)
            throws LogoutException, SQLException {
        CLDRLocale locale = CLDRLocale.getInstance(file.getLocaleID());
        String currentWinner = file.getStringValue(path);
        boolean didVote = box.userDidVote(getMyUser(), path);
        StackTraceElement them = StackTracker.currentElement(0);
        String where = " (" + them.getFileName() + ":" + them.getLineNumber() + "): ";

        if (expectString == null) expectString = NULL;
        if (currentWinner == null) currentWinner = NULL;

        if (expectString != ANY && !expectString.equals(currentWinner)) {
            throw new IllegalArgumentException(
                    "ERR:"
                            + where
                            + "Expected '"
                            + expectString
                            + "': "
                            + locale
                            + ":"
                            + path
                            + " ='"
                            + currentWinner
                            + "', "
                            + votedToString(didVote)
                            + box.getResolver(path));
        } else if (expectVoted != didVote) {
            throw new IllegalArgumentException(
                    "ERR:"
                            + where
                            + "Expected VOTING="
                            + votedToString(expectVoted)
                            + ":  "
                            + locale
                            + ":"
                            + path
                            + " ='"
                            + currentWinner
                            + "', "
                            + votedToString(didVote)
                            + box.getResolver(path));
        } else {
            System.out.println(
                    where
                            + ":"
                            + locale
                            + ":"
                            + path
                            + " ='"
                            + currentWinner
                            + "', "
                            + votedToString(didVote)
                            + box.getResolver(path));
        }
        return currentWinner;
    }

    /**
     * @param didVote
     * @return
     */
    private String votedToString(boolean didVote) {
        return didVote ? "(I VOTED)" : "( did NOT VOTE) ";
    }

    @Test
    public void TestBasicVote()
            throws SQLException, IOException, InvalidXPathException, VoteNotAcceptedException,
                    LogoutException {
        STFactory fac = getFactory();

        final String somePath = "//ldml/localeDisplayNames/keys/key[@type=\"collation\"]";
        String originalValue = null;
        String changedTo = null;

        CLDRLocale locale = CLDRLocale.getInstance("de");
        CLDRLocale localeSub = CLDRLocale.getInstance("de_CH");
        {
            CLDRFile mt = fac.make(locale, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale);

            originalValue = expect(somePath, ANY, false, mt, box);

            changedTo = "The main pump fixing screws with the correct strength class"; // as
            // per
            // ticket:2260

            if (originalValue.equals(changedTo)) {
                fail(
                        "for "
                                + locale
                                + " value "
                                + somePath
                                + " winner is already= "
                                + originalValue);
            }

            box.voteForValue(getMyUser(), somePath, changedTo); // vote
            expect(somePath, changedTo, true, mt, box);

            box.voteForValue(getMyUser(), somePath, null); // unvote
            expect(somePath, originalValue, false, mt, box);

            box.voteForValue(getMyUser(), somePath, changedTo); // vote again
            expect(somePath, changedTo, true, mt, box);

            Date modDate = mt.getLastModifiedDate(somePath);
            assertNotNull(modDate, "@1: mod date was null!");
            System.out.println("@1: mod date " + modDate);
        }

        // Restart STFactory.
        fac = resetFactory();
        {
            CLDRFile mt = fac.make(locale, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale);

            expect(somePath, changedTo, true, mt, box);

            {
                Date modDate = mt.getLastModifiedDate(somePath);
                assertNotNull(modDate, "@2: mod date was null!");
                System.out.println("@2: mod date " + modDate);
            }
            CLDRFile mt2 = fac.make(locale, true);
            {
                Date modDate = mt2.getLastModifiedDate(somePath);
                assertNotNull(modDate, "@2a: mod date was null!");
                System.out.println("@2a: mod date " + modDate);
            }
            CLDRFile mtMT = fac.make(localeSub, true);
            {
                Date modDate = mtMT.getLastModifiedDate(somePath);
                assertNotNull(modDate, "@2b: mod date was null!");
                System.out.println("@2b: mod date " + modDate);
            }
            CLDRFile mtMTb = fac.make(localeSub, false);
            {
                Date modDate = mtMTb.getLastModifiedDate(somePath);
                assertNotNull(modDate, "@2c: mod date was null!");
                System.out.println("@2c: mod date " + modDate);
            }
            // unvote
            box.voteForValue(getMyUser(), somePath, null);

            expect(somePath, originalValue, false, mt, box);
            {
                Date modDate = mt.getLastModifiedDate(somePath);
                assertNotNull(modDate, "@3: mod date was null!");
                System.out.println("@3: mod date " + modDate);
            }
        }
        fac = resetFactory();
        {
            CLDRFile mt = fac.make(locale, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale);

            expect(somePath, originalValue, false, mt, box);

            // vote for ____2
            changedTo = changedTo + "2";

            System.out.println("VoteFor: " + changedTo);
            box.voteForValue(getMyUser(), somePath, changedTo);

            expect(somePath, changedTo, true, mt, box);

            System.out.println("Write out..");
            File targDir = TestAll.getEmptyDir(TestSTFactory.class.getName() + "_output");
            File outFile = new File(targDir, locale.getBaseName() + ".xml");
            PrintWriter pw =
                    FileUtilities.openUTF8Writer(
                            targDir.getAbsolutePath(), locale.getBaseName() + ".xml");
            mt.write(pw, noDtdPlease);
            pw.close();

            System.out.println("Read back..");
            CLDRFile readBack = null;
            readBack =
                    CLDRFile.loadFromFile(outFile, locale.getBaseName(), DraftStatus.unconfirmed);
            String reRead = readBack.getStringValue(somePath);

            System.out.println(
                    "reread:  "
                            + outFile.getAbsolutePath()
                            + " value "
                            + somePath
                            + " = "
                            + reRead);
            if (!changedTo.equals(reRead)) {
                System.out.println(
                        "reread:  "
                                + outFile.getAbsolutePath()
                                + " value "
                                + somePath
                                + " = "
                                + reRead
                                + ", should be "
                                + changedTo);
            }
        }
    }

    @Test
    public void TestDenyVote() throws SQLException, IOException {
        STFactory fac = getFactory();
        final String somePath2 = "//ldml/localeDisplayNames/keys/key[@type=\"numbers\"]";
        // String originalValue2 = null;
        String changedTo2 = null;
        // test votring for a bad locale
        {
            CLDRLocale locale2 = CLDRLocale.getInstance("mt_MT");
            // CLDRFile mt_MT = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            try {
                box.voteForValue(getMyUser(), somePath2, changedTo2);
                fail("Error! should have failed to vote for " + locale2);
            } catch (Throwable t) {
                System.out.println(
                        "Good - caught " + t.toString() + " as this locale is a default content.");
            }
        }
        {
            CLDRLocale locale2 = CLDRLocale.getInstance("en");
            // CLDRFile mt_MT = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            try {
                box.voteForValue(getMyUser(), somePath2, changedTo2);
                fail("Error! should have failed to vote for " + locale2);
            } catch (Throwable t) {
                System.out.println(
                        "Good - caught " + t.toString() + " as this locale is readonly english.");
            }
        }
        {
            CLDRLocale locale2 = CLDRLocale.getInstance("no");
            // CLDRFile no = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);
            final String bad_xpath =
                    "//ldml/units/unitLength[@type=\"format\"]/unit[@type=\"murray\"]/unitPattern[@count=\"many\"]";

            try {
                box.voteForValue(getMyUser(), bad_xpath, "{0} Murrays"); // bogus
                fail("Error! should have failed to vote for " + locale2 + " xpath " + bad_xpath);
            } catch (Throwable t) {
                System.out.println(
                        "Good - caught "
                                + t.toString()
                                + " voting for "
                                + bad_xpath
                                + " as this is a bad xpath.");
            }
        }
    }

    @Test
    public void TestSparseVote()
            throws SQLException, IOException, InvalidXPathException, SurveyException,
                    LogoutException {
        STFactory fac = getFactory();

        final String somePath2 = "//ldml/localeDisplayNames/keys/key[@type=\"calendar\"]";
        String originalValue2 = null;
        String changedTo2 = null;
        CLDRLocale locale2 = CLDRLocale.getInstance("fr_BE");
        // Can't (and shouldn't) try to do this test if the locale is configured as read-only
        // (including algorithmic).
        if (SpecialLocales.Type.isReadOnly(SpecialLocales.getType(locale2))) {
            return;
        }

        // test sparsity
        {
            CLDRFile cldrFile = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            originalValue2 = expect(somePath2, null, false, cldrFile, box);

            changedTo2 = "The alternate pump fixing screws with the incorrect strength class";

            if (originalValue2.equals(changedTo2)) {
                fail(
                        "for "
                                + locale2
                                + " value "
                                + somePath2
                                + " winner is already= "
                                + originalValue2);
            }

            box.voteForValue(getMyUser(), somePath2, changedTo2);

            expect(somePath2, changedTo2, true, cldrFile, box);
        }
        // Restart STFactory.
        fac = resetFactory();
        {
            CLDRFile cldrFile = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            expect(somePath2, changedTo2, true, cldrFile, box);

            // unvote
            box.voteForValue(getMyUser(), somePath2, null);

            /*
             * No one has voted; expect inheritance to win
             */
            expect(somePath2, CldrUtility.INHERITANCE_MARKER, false, cldrFile, box);
        }
        fac = resetFactory();
        {
            CLDRFile cldrFile = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            expect(somePath2, null, false, cldrFile, box);

            // vote for ____2
            changedTo2 = changedTo2 + "2";

            System.out.println("VoteFor: " + changedTo2);
            box.voteForValue(getMyUser(), somePath2, changedTo2);

            expect(somePath2, changedTo2, true, cldrFile, box);

            System.out.println("Write out..");
            File targDir = TestAll.getEmptyDir(TestSTFactory.class.getName() + "_output");
            File outFile = new File(targDir, locale2.getBaseName() + ".xml");
            PrintWriter pw =
                    FileUtilities.openUTF8Writer(
                            targDir.getAbsolutePath(), locale2.getBaseName() + ".xml");
            cldrFile.write(pw, noDtdPlease);
            pw.close();

            System.out.println("Read back..");
            CLDRFile readBack =
                    CLDRFile.loadFromFile(outFile, locale2.getBaseName(), DraftStatus.unconfirmed);

            String reRead = readBack.getStringValue(somePath2);

            System.out.println(
                    "reread:  "
                            + outFile.getAbsolutePath()
                            + " value "
                            + somePath2
                            + " = "
                            + reRead);
            if (!changedTo2.equals(reRead)) {
                System.out.println(
                        "reread:  "
                                + outFile.getAbsolutePath()
                                + " value "
                                + somePath2
                                + " = "
                                + reRead
                                + ", should be "
                                + changedTo2);
            }
        }
    }

    @Test
    public void TestVettingDataDriven() throws SQLException, IOException {
        runDataDrivenTest(TestSTFactory.class.getSimpleName()); // TestSTFactory.xml
    }

    @Test
    public void TestVotePerf() throws SQLException, IOException {
        final CheckCLDR.Phase p = CLDRConfig.getInstance().getPhase();
        assertTrue(p.isUnitTest(), "phase " + p + ".isUnitTest()");
        runDataDrivenTest("TestVotePerf");
    }

    public void TestUserRegistry() throws SQLException, IOException {
        runDataDrivenTest("TestUserRegistry");
    }

    /*
     * TODO: shorten this function, over 300 lines; use subroutines
     */
    private void runDataDrivenTest(final String fileBasename) throws SQLException, IOException {
        final STFactory fac = getFactory();
        final File targDir = TestAll.getEmptyDir(TestSTFactory.class.getName() + "_output");

        XMLFileReader myReader = new XMLFileReader();
        final Map<String, String> attrs = new TreeMap<>();
        final Map<String, String> vars = new TreeMap<>();
        myReader.setHandler(
                new XMLFileReader.SimpleHandler() {
                    final Map<String, UserRegistry.User> users = new TreeMap<>();
                    int pathCount = 0;

                    @Override
                    public void handlePathValue(String path, String value) {
                        ++pathCount;
                        if (value != null && value.startsWith("$")) {
                            String varName = value.substring(1);
                            value = vars.get(varName);
                            System.out.println(" $" + varName + " == '" + value + "'");
                        }

                        XPathParts xpp = XPathParts.getFrozenInstance(path);
                        attrs.clear();
                        for (String k : xpp.getAttributeKeys(-1)) {
                            attrs.put(k, xpp.getAttributeValue(-1, k));
                        }
                        if ("mul_ZZ".equals(attrs.get("locale"))) {
                            int debug = 0;
                        }

                        String elem = xpp.getElement(-1);
                        if (false)
                            System.out.println(
                                    "* <"
                                            + elem
                                            + " "
                                            + attrs.toString()
                                            + ">"
                                            + value
                                            + "</"
                                            + elem
                                            + ">");
                        String xpath = attrs.get("xpath");
                        if (xpath != null) {
                            xpath = xpath.trim().replace("'", "\"");
                        }
                        switch (elem) {
                            case "user":
                                handleElementUser(fac, attrs);
                                break;
                            case "setvar":
                                handleElementSetvar(fac, attrs, vars, xpath);
                                break;
                            case "apivote":
                            case "apiunvote":
                                handleElementApivote(attrs, value, elem, xpath);
                                break;
                            case "vote":
                            case "unvote":
                                handleElementVote(fac, attrs, value, elem, xpath);
                                break;
                            case "apiverify":
                                handleElementApiverify(attrs, value, xpath);
                                break;
                            case "verify":
                                try {
                                    handleElementVerify(fac, attrs, value, elem, xpath);
                                } catch (IOException e) {
                                    fail(e);
                                }
                                break;
                            case "verifyUser":
                                handleElementVerifyUser(attrs);
                                break;
                            case "echo":
                            case "warn":
                                handleElementEcho(value, elem);
                                break;
                            default:
                                throw new IllegalArgumentException(
                                        "Unknown test element type " + elem);
                        }
                    }

                    private void handleElementVerify(
                            STFactory fac,
                            Map<String, String> attrs,
                            String value,
                            String elem,
                            String xpath)
                            throws IOException {
                        value = value.trim();
                        if (value.isEmpty()) value = null;
                        CLDRLocale locale = CLDRLocale.getInstance(attrs.get("locale"));
                        BallotBox<User> box = fac.ballotBoxForLocale(locale);
                        CLDRFile cf = fac.make(locale, true);
                        String stringValue = cf.getStringValue(xpath);
                        String fullXpath = cf.getFullXPath(xpath);
                        // System.out.println("V"+ xpath + " = " + stringValue + ", " +
                        // fullXpath);
                        // System.out.println("Resolver=" + box.getResolver(xpath));
                        if (value == null && stringValue != null) {
                            fail(
                                    pathCount
                                            + "a Expected null value at "
                                            + locale
                                            + ":"
                                            + xpath
                                            + " got "
                                            + stringValue);
                        } else if (value != null && !value.equals(stringValue)) {
                            fail(
                                    pathCount
                                            + "b Expected "
                                            + value
                                            + " at "
                                            + locale
                                            + ":"
                                            + xpath
                                            + " got "
                                            + stringValue);
                        } else {
                            System.out.println("OK: " + locale + ":" + xpath + " = " + value);
                        }
                        Status expStatus = Status.fromString(attrs.get("status"));

                        VoteResolver<String> r = box.getResolver(xpath);
                        Status winStatus = r.getWinningStatus();
                        if (winStatus == expStatus) {
                            System.out.println(
                                    "OK: Status="
                                            + winStatus
                                            + " "
                                            + locale
                                            + ":"
                                            + xpath
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        } else if (pathCount == 49 && !VoteResolver.DROP_HARD_INHERITANCE) {
                            System.out.println(
                                    "Ignoring status mismatch for "
                                            + pathCount
                                            + "c, test assumes DROP_HARD_INHERITANCE is true");
                        } else {
                            fail(
                                    pathCount
                                            + "c Expected: Status="
                                            + expStatus
                                            + " got "
                                            + winStatus
                                            + " "
                                            + locale
                                            + ":"
                                            + xpath
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        }

                        Status xpathStatus;
                        CLDRFile.Status newPath = new CLDRFile.Status();
                        CLDRLocale newLocale =
                                CLDRLocale.getInstance(
                                        cf.getSourceLocaleIdExtended(fullXpath, newPath, false));
                        final boolean localeChanged =
                                newLocale != null && !newLocale.equals(locale);
                        final boolean pathChanged =
                                newPath.pathWhereFound != null
                                        && !newPath.pathWhereFound.equals(xpath);
                        final boolean itMoved = localeChanged || pathChanged;
                        if (localeChanged && pathChanged) {
                            System.out.println(
                                    "Aliased(locale+path): "
                                            + locale
                                            + "->"
                                            + newLocale
                                            + " and "
                                            + xpath
                                            + "->"
                                            + newPath.pathWhereFound);
                        } else if (localeChanged) {
                            System.out.println("Aliased(locale): " + locale + "->" + newLocale);
                        } else if (pathChanged) {
                            System.out.println(
                                    "Aliased(path): " + xpath + "->" + newPath.pathWhereFound);
                        }
                        if ((fullXpath == null) || itMoved) {
                            xpathStatus = Status.missing;
                        } else {
                            XPathParts xpp2 = XPathParts.getFrozenInstance(fullXpath);
                            String statusFromXpath = xpp2.getAttributeValue(-1, "draft");

                            if (statusFromXpath == null) {
                                statusFromXpath = "approved"; // no draft = approved
                            }
                            xpathStatus = Status.fromString(statusFromXpath);
                        }
                        if (xpathStatus != winStatus) {
                            System.out.println(
                                    "Warning: Winning Status="
                                            + winStatus
                                            + " but xpath status is "
                                            + xpathStatus
                                            + " "
                                            + locale
                                            + ":"
                                            + fullXpath
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        } else if (xpathStatus == expStatus) {
                            System.out.println(
                                    "OK from fullxpath: Status="
                                            + xpathStatus
                                            + " "
                                            + locale
                                            + ":"
                                            + fullXpath
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        } else {
                            fail(
                                    pathCount
                                            + "d Expected from fullxpath: Status="
                                            + expStatus
                                            + " got "
                                            + xpathStatus
                                            + " "
                                            + locale
                                            + ":"
                                            + fullXpath
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        }

                        // Verify from XML
                        File outFile = new File(targDir, locale.getBaseName() + ".xml");
                        if (outFile.exists()) outFile.delete();
                        PrintWriter pw;
                        pw =
                                FileUtilities.openUTF8Writer(
                                        targDir.getAbsolutePath(), locale.getBaseName() + ".xml");
                        cf.write(pw, noDtdPlease);
                        pw.close();

                        // System.out.println("Read back..");
                        CLDRFile readBack = null;
                        readBack =
                                CLDRFile.loadFromFile(
                                        outFile, locale.getBaseName(), DraftStatus.unconfirmed);
                        String reRead = readBack.getStringValue(xpath);
                        String fullXpathBack = readBack.getFullXPath(xpath);
                        Status xpathStatusBack;
                        if (fullXpathBack == null || itMoved) {
                            xpathStatusBack = Status.missing;
                        } else {
                            XPathParts xpp2 = XPathParts.getFrozenInstance(fullXpathBack);
                            String statusFromXpathBack = xpp2.getAttributeValue(-1, "draft");

                            if (statusFromXpathBack == null) {
                                statusFromXpathBack = "approved"; // no draft =
                                // approved
                            }
                            xpathStatusBack = Status.fromString(statusFromXpathBack);
                        }

                        if (value == null && reRead != null) {
                            fail(
                                    pathCount
                                            + "e Expected null value from XML at "
                                            + locale
                                            + ":"
                                            + xpath
                                            + " got "
                                            + reRead);
                        } else if (value != null && !value.equals(reRead)) {
                            fail(
                                    pathCount
                                            + "f Expected from XML "
                                            + value
                                            + " at "
                                            + locale
                                            + ":"
                                            + xpath
                                            + " got "
                                            + reRead);
                        } else {
                            System.out.println(
                                    "OK from XML: " + locale + ":" + xpath + " = " + reRead);
                        }

                        if (xpathStatusBack == expStatus) {
                            System.out.println(
                                    "OK from XML: Status="
                                            + xpathStatusBack
                                            + " "
                                            + locale
                                            + ":"
                                            + fullXpathBack
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        } else if (xpathStatusBack != winStatus) {
                            System.out.println(
                                    "Warning: Problem from XML: Winning Status="
                                            + winStatus
                                            + " got "
                                            + xpathStatusBack
                                            + " "
                                            + locale
                                            + ":"
                                            + fullXpathBack
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        } else {
                            fail(
                                    pathCount
                                            + "g Expected from XML: Status="
                                            + expStatus
                                            + " got "
                                            + xpathStatusBack
                                            + " "
                                            + locale
                                            + ":"
                                            + fullXpathBack
                                            + " Resolver="
                                            + box.getResolver(xpath));
                        }
                        verifyOrgStatus(r, attrs);
                    }

                    private void handleElementEcho(String value, String elem) {
                        if (value == null) {
                            System.out.println("*** " + elem + "  \"" + "null" + "\"");
                        } else {
                            System.out.println("*** " + elem + "  \"" + value.trim() + "\"");
                        }
                    }

                    private void handleElementVerifyUser(final Map<String, String> attrs) {
                        final User u = getUserFromAttrs(attrs, "name");
                        final User onUser = getUserFromAttrs(attrs, "onUser");
                        final String action = attrs.get("action");
                        final boolean allowed = getBooleanAttr(attrs, "allowed", true);
                        boolean actualResult = true;
                        //                    <!ATTLIST verifyUser action ( create |
                        // delete | modify | list ) #REQUIRED>
                        final Level uLevel = u.getLevel();
                        final Level onLevel = onUser.getLevel();
                        switch (action) {
                            case "create":
                                actualResult = actualResult && UserRegistry.userCanCreateUsers(u);
                                if (!u.isSameOrg(onUser)) {
                                    actualResult =
                                            actualResult
                                                    && UserRegistry.userCreateOtherOrgs(
                                                            u); // if of different org
                                }
                                actualResult =
                                        actualResult && uLevel.canCreateOrSetLevelTo(onLevel);
                                break;
                            case "delete": // assume same perms for now (?)
                            case "modify":
                                {
                                    final boolean oldTest = u.isAdminFor(onUser);
                                    final boolean newTest =
                                            uLevel.canManageSomeUsers()
                                                    && uLevel.isManagerFor(
                                                            u.getOrganization(),
                                                            onLevel,
                                                            onUser.getOrganization());
                                    assertEquals(
                                            newTest,
                                            oldTest,
                                            "New(ex) vs old(got) manage test: "
                                                    + uLevel
                                                    + "/"
                                                    + onLevel);
                                    actualResult = actualResult && newTest;
                                }
                                break;
                            default:
                                fail("Unhandled action: " + action);
                        }
                        assertEquals(
                                allowed,
                                actualResult,
                                u.org
                                        + ":"
                                        + uLevel
                                        + " "
                                        + action
                                        + " "
                                        + onUser.org
                                        + ":"
                                        + onLevel);
                    }

                    private void handleElementApiverify(
                            final Map<String, String> attrs, String value, String xpath) {
                        // like verify, but via API
                        value = value.trim();
                        if (value.isEmpty()) value = null;
                        UserRegistry.User u = getUserFromAttrs(attrs, "name");
                        CLDRLocale locale = CLDRLocale.getInstance(attrs.get("locale"));
                        final CookieSession mySession = CookieSession.getTestSession(u);
                        ArgsForGet args = new ArgsForGet(locale.getBaseName(), mySession.id);
                        args.xpstrid = XPathTable.getStringIDString(xpath);
                        // args.getDashboard = false;
                        try {
                            final RowResponse r =
                                    VoteAPIHelper.getRowsResponse(
                                            args, CookieSession.sm, locale, mySession, false);
                            assertEquals( args.xpstrid, r.xpstrid, "xpstrid");
                            assertEquals(1, r.page.rows.size(), "row count");
                            final Row firstRow = r.page.rows.values().iterator().next();
                            assertEquals(firstRow.xpath, xpath, "rxpath");
                            assertEquals(value, firstRow.winningValue, "value for " + args.xpstrid);
                        } catch (SurveyException t) {
                            assertNull(t, "did not expect an exception");
                        }
                    }

                    private void handleElementVote(
                            final STFactory fac,
                            final Map<String, String> attrs,
                            String value,
                            String elem,
                            String xpath) {
                        UserRegistry.User u = getUserFromAttrs(attrs, "name");
                        CLDRLocale locale = CLDRLocale.getInstance(attrs.get("locale"));
                        BallotBox<User> box = fac.ballotBoxForLocale(locale);
                        value = value.trim();
                        boolean needException = getBooleanAttr(attrs, "exception", false);
                        if (elem.equals("unvote")) {
                            value = null;
                        }
                        try {
                            box.voteForValue(u, xpath, value);
                            if (needException) {
                                fail(
                                        "ERR: path #"
                                                + pathCount
                                                + ", xpath="
                                                + xpath
                                                + ", locale="
                                                + locale
                                                + ": expected exception, didn't get one");
                            }
                        } catch (InvalidXPathException e) {
                            fail("Error: invalid xpath exception " + xpath + " : " + e);
                        } catch (VoteNotAcceptedException iae) {
                            if (needException == true) {
                                System.out.println("Caught expected: " + iae);
                            } else {
                                iae.printStackTrace();
                                fail("Unexpected exception: " + iae);
                            }
                        }
                        System.out.println(u + " " + elem + "d for " + xpath + " = " + value);
                    }

                    private void handleElementApivote(
                            final Map<String, String> attrs,
                            String value,
                            String elem,
                            String xpath) {
                        UserRegistry.User u = getUserFromAttrs(attrs, "name");
                        CLDRLocale locale = CLDRLocale.getInstance(attrs.get("locale"));
                        boolean needException = getBooleanAttr(attrs, "exception", false);
                        if (elem.equals("apiunvote")) {
                            value = null;
                        }
                        final CookieSession mySession = CookieSession.getTestSession(u);
                        try {
                            final VoteAPI.VoteResponse r =
                                    VoteAPIHelper.getHandleVoteResponse(
                                            locale.getBaseName(),
                                            xpath,
                                            value,
                                            0,
                                            mySession,
                                            false);
                            final boolean isOk = r.didVote;
                            final boolean asExpected = (isOk == !needException);
                            if (!asExpected) {
                                fail(
                                        "exception="
                                                + needException
                                                + " but got status "
                                                + r.didNotSubmit
                                                + " - "
                                                + r.toString());
                            } else {
                                System.out.println(" status = " + r.didNotSubmit);
                            }
                        } catch (Throwable iae) {
                            if (needException == true) {
                                System.out.println("Caught expected: " + iae);
                            } else {
                                iae.printStackTrace();
                                fail("Unexpected exception: " + iae);
                            }
                        }
                    }

                    private void handleElementSetvar(
                            final STFactory fac,
                            final Map<String, String> attrs,
                            final Map<String, String> vars,
                            String xpath) {
                        final String id = attrs.get("id");
                        final CLDRLocale locale = CLDRLocale.getInstance(attrs.get("locale"));
                        final String xvalue = fac.make(locale, true).getStringValue(xpath);
                        vars.put(id, xvalue);
                        System.out.println(
                                "$" + id + " = '" + xvalue + "' from " + locale + ":" + xpath);
                    }

                    private void handleElementUser(
                            final STFactory fac, final Map<String, String> attrs)
                            throws InternalError {
                        String name = attrs.get("name");
                        String org = attrs.get("org");
                        String locales = attrs.get("locales");
                        VoteResolver.Level level =
                                VoteResolver.Level.valueOf(attrs.get("level").toLowerCase());
                        String email = name + "@" + org + ".example.com";
                        UserRegistry.User u = fac.sm.reg.get(email);
                        if (u == null) {
                            u = fac.sm.reg.createTestUser(name, org, locales, level, email);
                        }
                        if (u == null) {
                            throw new InternalError("Couldn't find/register user " + name);
                        } else {
                            System.out.println(name + " = " + u);
                            users.put(name, u);
                        }
                    }

                    /**
                     * If a "verify" element includes "orgStatus" and "statusOrg" attributes, then
                     * report an error unless getStatusForOrganization returns the specified status
                     * for the specified org
                     *
                     * @param r the VoteResolver
                     * @param attrs the attributes
                     */
                    private void verifyOrgStatus(
                            VoteResolver<String> r, Map<String, String> attrs) {
                        final String expOrgStatus = attrs.get("orgStatus"); // e.g., "ok"
                        final String expStatusOrg = attrs.get("statusOrg"); // e.g., "apple"
                        if (expOrgStatus != null && expStatusOrg != null) {
                            final Organization org = Organization.fromString(expStatusOrg);
                            final String actualOrgStatus =
                                    r.getStatusForOrganization(org).toString();
                            if (!expOrgStatus.equals(actualOrgStatus)) {
                                fail(
                                        "Error: expected + "
                                                + expOrgStatus
                                                + " got "
                                                + actualOrgStatus
                                                + " for "
                                                + expStatusOrg);
                            }
                        }
                    }

                    /**
                     * @param attrs
                     * @return
                     */
                    public boolean getBooleanAttr(
                            final Map<String, String> attrs, String attr, boolean defaultValue) {
                        final String strVal = attrs.get(attr);
                        if (strVal == null || strVal.isEmpty()) {
                            return defaultValue;
                        }
                        return Boolean.parseBoolean(strVal);
                    }

                    /**
                     * @param attrs
                     * @param attr
                     * @return
                     * @throws IllegalArgumentException
                     */
                    public UserRegistry.User getUserFromAttrs(
                            final Map<String, String> attrs, String attr)
                            throws IllegalArgumentException {
                        final String attrValue = attrs.get(attr);
                        if (attrValue == null) {
                            return null;
                        }
                        UserRegistry.User u = users.get(attrValue);
                        if (u == null) {
                            throw new IllegalArgumentException(
                                    "Undeclared user: "
                                            + attr
                                            + "=\""
                                            + attrValue
                                            + "\" - are you missing a <user> element?");
                        }
                        return u;
                    }
                    // public void handleComment(String path, String comment) {};
                    // public void handleElementDecl(String name, String model) {};
                    // public void handleAttributeDecl(String eName, String aName,
                    // String type, String mode, String value) {};
                });
        final String fileName = fileBasename + ".xml";
        myReader.read(
                TestSTFactory.class
                        .getResource("data/" + fileName)
                        .toString(), // for DTD resolution
                getUTF8Data(fileName),
                -1,
                true);
    }

    /**
     * Fetch data from jar
     *
     * @param name name of thing to load (org.unicode.cldr.web.data.name)
     */
    public static BufferedReader getUTF8Data(String name) throws java.io.IOException {
        return FileReaders.openFile(STFactory.class, "data/" + name);
    }

    @Test
    public void TestVettingWithNonDistinguishing()
            throws SQLException, IOException, InvalidXPathException, SurveyException,
                    LogoutException {
        if (TestAll.skipIfNoDb()) return;
        STFactory fac = getFactory();

        final String somePath2 =
                "//ldml/dates/calendars/calendar[@type=\"hebrew\"]/dateFormats/dateFormatLength[@type=\"full\"]/dateFormat[@type=\"standard\"]/pattern[@type=\"standard\"]";
        String originalValue2 = null;
        String changedTo2 = null;
        CLDRLocale locale2 = CLDRLocale.getInstance("he");
        String fullPath = null;
        {
            CLDRFile mt_MT = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            originalValue2 = expect(somePath2, ANY, false, mt_MT, box);

            fullPath = mt_MT.getFullXPath(somePath2);
            System.out.println("locale " + locale2 + " path " + somePath2 + " full = " + fullPath);
            if (!fullPath.contains("numbers=")) {
                System.out.println(
                        "Warning: "
                                + locale2
                                + ":"
                                + somePath2
                                + " fullpath doesn't contain numbers= - test skipped, got path "
                                + fullPath);
                return;
            }

            changedTo2 = "EEEE, d _MMMM y";

            if (originalValue2.equals(changedTo2)) {
                fail(
                        "for "
                                + locale2
                                + " value "
                                + somePath2
                                + " winner is already= "
                                + originalValue2);
            }

            box.voteForValue(getMyUser(), somePath2, changedTo2);

            expect(somePath2, changedTo2, true, mt_MT, box);
        }
        // Restart STFactory.
        fac = resetFactory();
        {
            CLDRFile mt_MT = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            expect(somePath2, changedTo2, true, mt_MT, box);

            // unvote
            box.voteForValue(getMyUser(), somePath2, null);

            expect(somePath2, originalValue2, false, mt_MT, box); // Expect
            // original
            // value - no
            // one has
            // voted.

            String fullPath2 = mt_MT.getFullXPath(somePath2);
            if (!fullPath2.contains("numbers=")) {
                fail("Error - voted, but full path lost numbers= - " + fullPath2);
            }
        }
        fac = resetFactory();
        {
            CLDRFile mt_MT = fac.make(locale2, false);
            BallotBox<User> box = fac.ballotBoxForLocale(locale2);

            expect(somePath2, originalValue2, false, mt_MT, box); // still
            // original
            // value

            // vote for ____2
            changedTo2 = changedTo2 + "__";

            System.out.println("VoteFor: " + changedTo2);
            box.voteForValue(getMyUser(), somePath2, changedTo2);

            expect(somePath2, changedTo2, true, mt_MT, box);

            System.out.println("Write out..");
            File targDir = TestAll.getEmptyDir(TestSTFactory.class.getName() + "_output");
            File outFile = new File(targDir, locale2.getBaseName() + ".xml");
            PrintWriter pw =
                    FileUtilities.openUTF8Writer(
                            targDir.getAbsolutePath(), locale2.getBaseName() + ".xml");
            mt_MT.write(pw, noDtdPlease);
            pw.close();

            System.out.println("Read back..");
            CLDRFile readBack =
                    CLDRFile.loadFromFile(outFile, locale2.getBaseName(), DraftStatus.unconfirmed);

            String reRead = readBack.getStringValue(somePath2);

            System.out.println(
                    "reread:  "
                            + outFile.getAbsolutePath()
                            + " value "
                            + somePath2
                            + " = "
                            + reRead);
            if (!changedTo2.equals(reRead)) {
                System.out.println(
                        "reread:  "
                                + outFile.getAbsolutePath()
                                + " value "
                                + somePath2
                                + " = "
                                + reRead
                                + ", should be "
                                + changedTo2);
            }
            String fullPath2 = readBack.getFullXPath(somePath2);
            if (!fullPath2.contains("numbers=")) {
                fail("Error - readBack's full path lost numbers= - " + fullPath2);
            }
        }
    }

    private void verifyReadOnly(CLDRFile f) {
        String loc = f.getLocaleID();
        try {
            f.add("//ldml/foo", "bar");
            fail("Error: " + loc + " is supposed to be readonly.");
        } catch (Throwable t) {
            System.out.println("Pass: " + loc + " is readonly, caught " + t.toString());
        }
    }

    public UserRegistry.User getMyUser() throws LogoutException, SQLException {
        if (gUser == null) {
            gUser = getFactory().sm.reg.get(null, UserRegistry.ADMIN_EMAIL, "[::1]", true);
        }
        return gUser;
    }

    private STFactory getFactory() throws SQLException {
        if (gFac == null) {
            gFac = createFactory();
        }
        return gFac;
    }

    private STFactory resetFactory() throws SQLException {
        if (gFac == null) {
            System.out.println("STFactory wasn't loaded - not resetting.");
            return getFactory();
        } else {
            System.out.println("--- resetting STFactory() ----- [simulate reload] ------------");
            return gFac = getFactory().TESTING_shutdownAndRestart();
        }
    }

    public static STFactory createFactory() throws SQLException {
        long start = System.currentTimeMillis();
        TestAll.setupTestDb();
        System.err.println("Set up test DB: " + ElapsedTimer.elapsedTime(start));

        ElapsedTimer et0 = new ElapsedTimer("clearing directory");
        // File cacheDir = TestAll.getEmptyDir(CACHETEST);
        System.err.println(et0.toString());

        et0 = new ElapsedTimer("setup SurveyMain");
        SurveyMain sm = new SurveyMain();
        CookieSession.sm = sm; // hack - of course.
        System.err.println(et0.toString());

        SurveyMain.fileBase = CLDRPaths.MAIN_DIRECTORY;
        SurveyMain.fileBaseSeed =
                new File(CLDRPaths.BASE_DIRECTORY, "seed/main/").getAbsolutePath();
        SurveyMain.fileBaseA =
                new File(CLDRPaths.BASE_DIRECTORY, "common/annotations/").getAbsolutePath();
        SurveyMain.fileBaseASeed =
                new File(CLDRPaths.BASE_DIRECTORY, "seed/annotations/").getAbsolutePath();

        et0 = new ElapsedTimer("setup DB");
        Connection conn = DBUtils.getInstance().getDBConnection();
        System.err.println(et0.toString());

        et0 = new ElapsedTimer("setup Registry");
        sm.reg = UserRegistry.createRegistry(sm);
        System.err.println(et0.toString());

        et0 = new ElapsedTimer("setup XPT");
        sm.xpt = XPathTable.createTable(conn);
        sm.xpt.getByXpath("//foo/bar[@type='baz']");
        System.err.println(et0.toString());
        et0 = new ElapsedTimer("close connection");
        DBUtils.closeDBConnection(conn);
        System.err.println(et0.toString());
        et0 = new ElapsedTimer("Set up STFactory");
        STFactory fac = sm.getSTFactory();
        System.err.println(et0.toString());

        org.junit.jupiter.api.Assertions.assertFalse(
                SurveyMain.isBusted(), "SurveyTool shouldn’t be busted!");
        return fac;
    }

    static final Map<String, Object> noDtdPlease = new TreeMap<>();

    static {
        noDtdPlease.put(
                "DTD_DIR", CLDRPaths.COMMON_DIRECTORY + File.separator + "dtd" + File.separator);
    }
}

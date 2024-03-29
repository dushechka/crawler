/*
 * Copyright (c) 2018 Andrey Fadeev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andreyfadeev.crawler;

import org.andreyfadeev.crawler.dbs.DBFactory;
import org.andreyfadeev.crawler.dbs.sql.orm.Page;
import org.andreyfadeev.crawler.interfaces.Index;
import org.andreyfadeev.crawler.interfaces.RatingsDatabase;
import org.andreyfadeev.crawler.interfaces.TermContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


/**
 * Crawls websites and gets
 * popularity of persons on them.
 *
 * @author Andrey Fadeev
 */
public class WebCrawler {
    private static final String ROBOTS_TXT_APPENDIX = "/robots.txt";
    private static final String MYSQL_PROPS_FILENAME = "/mysql_props.txt";
    private static final String REDIS_PROPS_FILENAME = "/redis_props.txt";
    private static final int PAGES_BEFORE_REINDEX = 1000;
    private static int PAGES_PER_SCAN_CYCLE = 10;
    private final DBFactory dbFactory;

    public WebCrawler(DBFactory dbFactory) throws IOException {
        this.dbFactory = dbFactory;
        setProperties();
    }

    /**
     * Inserts links to the robots.txt file
     * for sites, that have only one link in db.
     *
     * @param ratingsDb     database to work with
     * @throws SQLException
     */
    private void insertLinksToRobotsPages(RatingsDatabase ratingsDb) throws SQLException {
        Set<Page> pages = selectUnscannedPages(ratingsDb.getSinglePages());
        for (Page page : pages) {
            if (ratingsDb.getLastScanDate(page.getUrl()) == null) {
                ratingsDb.updateLastScanDate(page.getiD(), new Timestamp(System.currentTimeMillis()));
                try {
                    String address = LinksLoader.getSiteAddress(page.getUrl());
                    if (LinksLoader.isSiteAvailable(address)) {
                        System.out.println("Adding robots.txt link for " + address);
                        String robotsAddress = address + ROBOTS_TXT_APPENDIX;
                        ratingsDb.insertRowInPagesTable(robotsAddress, page.getSiteId(), null);
                        ratingsDb.updateLastScanDate(page.getiD(), null);
                    }
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (SQLException | IOException exc) {
                    exc.printStackTrace();
                    ratingsDb.updateLastScanDate(page.getiD(), null);
                }
            }
        }
    }

    /**
     * Selects unscanned pages
     * from collection.
     *
     * @param pages
     * @return  Set of pages, whose
     *          last scan dates are null.
     */
    private Set<Page> selectUnscannedPages(Set<Page> pages) {
        Set<Page> unscanned = new HashSet<>();
        for (Page page : pages) {
            if (page.getLastScanDate() == null) {
                unscanned.add(page);
            }
        }
        return unscanned;
    }

    /**
     * Saves links from robots.txt files,
     * contained in db to the same db.
     *
     * @param ratingsDb
     * @throws SQLException
     */
    private void fetchLinksFromRobotsTxt(RatingsDatabase ratingsDb) throws SQLException {
        Set<String> robotsTxtLinks = ratingsDb.getUnscannedRobotsTxtLinks();
        LinksLoader ln = new LinksLoader();
        for (String url : robotsTxtLinks) {
            if (ratingsDb.getLastScanDate(url) == null) {
                try {
                    ratingsDb.updateLastScanDate(url, new Timestamp(System.currentTimeMillis()));
                    Set<String> links = ln.getLinksFromRobotsTxt(url);
                    saveLinksToDb(url, links, ratingsDb);
                } catch (Exception exc) {
                    exc.printStackTrace();
                    ratingsDb.updateLastScanDate(url, null);
                }
            }
        }
    }

    /**
     * Saves links from sitemap.xml files,
     * contained in db to the same db.
     *
     * @param ratingsDb
     * @throws SQLException
     */
    private void fetchLinksFromSitemaps(RatingsDatabase ratingsDb) throws SQLException {
        LinksLoader ln = new LinksLoader();
        Set<String> links;
        do {
            links = ratingsDb.getUnscannedSitemapLinks();
            for (String link : links) {
                if (ratingsDb.getLastScanDate(link) == null) {
                    try {
                        ratingsDb.updateLastScanDate(link, new Timestamp(System.currentTimeMillis()));
                        saveLinksToDb(link, ln.getLinksFromSitemap(link), ratingsDb);
                    } catch (IOException | SQLException exc) {
                        ratingsDb.updateLastScanDate(link, null);
                        exc.printStackTrace();
                        return;
                    }
                }
            }
        } while (!links.isEmpty());
    }

    /**
     * Crawls unscanned pages from db
     * and saves ranks for persons
     * to the same database.
     *
     * @param ratingsDb db to get links and save ranks
     * @param index     Index to save vocabularies
     *                  from crawled pages.
     * @throws Exception
     */
    private void crawlUnscannedPages(RatingsDatabase ratingsDb, Index index) throws Exception {
        System.out.println("Maximum pages to scan per cycle: " + PAGES_PER_SCAN_CYCLE);
        System.out.println("Redis timeout: " + DBFactory.REDIS_TIMEOUT);
        Map<Integer, Set<String>> keywords = ratingsDb.getPersonsWithKeywords();
        HtmlParser parser = new HtmlParser();
        int cyclesPassed = 0;
        int errCounter = 0;
//        int siteId = 1;
        for (int siteId : ratingsDb.getSiteIds()) {
            Set<String> links;
            do {
                // determine, whether reindexing is needed
                if ((cyclesPassed * PAGES_PER_SCAN_CYCLE) > PAGES_BEFORE_REINDEX) {
                    cyclesPassed = 0;
                    int kwSize = keywords.size();
                    System.out.println("\n KWSIZE: " + kwSize);
                    keywords = ratingsDb.getPersonsWithKeywords();
                    System.out.println("NEW_KWSIZE: " + keywords.size());
                    System.out.println();
                    if (keywords.size() > kwSize) {
                        reindexPageRanks(ratingsDb, index);
                    }
                }
                System.out.println("\nGetting links for site with id=" + siteId);
                links = ratingsDb.getBunchOfUnscannedLinks(siteId, PAGES_PER_SCAN_CYCLE);
                ratingsDb.updateLastScanDatesByUrl(links, new Timestamp(System.currentTimeMillis()));
                System.out.println();
                Set<TermContainer> parsed;
                try {
                    parsed = parser.parsePages(links);
                    saveRanksToIndex(parsed, index);
                    errCounter = 0;
                    updatePersonsPageRanks(keywords, parsed, ratingsDb);
                } catch (Exception exc) {
                    errCounter++;
                    ratingsDb.updateLastScanDatesByUrl(links, null);
                    exc.printStackTrace();
                    if (errCounter > 7 ) {
                        throw new Exception(exc);
                    }
                }
                cyclesPassed++;
            } while (!links.isEmpty());
        }
    }

    /**
     * Saves term-rank vocabularies
     * for web-pages to the index.
     *
     * @param ranks
     * @param index
     */
    private void saveRanksToIndex(Set<TermContainer> ranks, Index index) {
        for (TermContainer tc : ranks) {
            index.putTerms(tc);
        }
    }

    /**
     *
     * @param keywords  Map from person id to
     *                  the set of it's keywords.
     * @param pageRanks
     * @param rdb       database to save ranks
     * @throws SQLException
     */
    private void updatePersonsPageRanks(Map<Integer, Set<String>> keywords,
                                        Set<TermContainer> pageRanks, RatingsDatabase rdb) throws SQLException {
        Map<Integer, Map<String, Integer>> personsPageRanks = new HashMap<>();
        for (TermContainer tc : pageRanks) {
            for (Integer personId : keywords.keySet()) {
                Map<String, Integer> ppr = new HashMap<>();
                for (String word : keywords.get(personId)) {
                    Integer count = tc.get(word.toLowerCase());
                    if (count > 0) {
                        System.out.println("Found entries for word " + word + ":");
                        System.out.printf("URL: %s, Count: %s\n", tc.getLabel(), count);
                        ppr.merge(tc.getLabel(), count, (first, second) -> first + second);
                    }
                }
                if (!ppr.isEmpty()) {
                    personsPageRanks.put(personId, ppr);
                }
            }
        }
        if (!personsPageRanks.isEmpty()) {
            System.out.println("Saving counts to DB...");
            for (Integer personId : personsPageRanks.keySet()) {
                rdb.insertPersonPageRanks(personId, personsPageRanks.get(personId));
            }
        }
    }

    /**
     * Inserts to db page ranks
     * from index for persons in db.
     * <p>
     *     This method inserts only
     *     those page ranks, which
     *     are not present in db.
     * </p>
     *
     * @param ratingsDb Database to get persons and
     *                  insert page ranks for them.
     * @param index     index to get page ranks
     * @throws SQLException
     */
    private void reindexPageRanks(RatingsDatabase ratingsDb, Index index) throws SQLException{
        System.out.println("\n*** STARTING REINDEXING ***");
        Map<Integer, Map<String, Integer>> personsPageRanks = ratingsDb.getPersonsPageRanks();
        Map<Integer, Map<String, Integer>> indexPpr = getPageRanksFromIndex(ratingsDb, index);
        for (Integer personId : personsPageRanks.keySet()) {
            Map<String, Integer> dbPageRandks = personsPageRanks.get(personId);
            Map<String, Integer> indexPageRanks = indexPpr.get(personId);
            for (String url : dbPageRandks.keySet()) {
                indexPageRanks.remove(url);
            }
            ratingsDb.insertPersonPageRanks(personId, indexPageRanks);
        }
    }

    /**
     *
     * @param url   Page url in db, by
     *              which we can get site id.
     * @param links links to save in db
     * @param db    database to save the links
     * @throws SQLException
     */
    private void saveLinksToDb(String url, Set<String> links,
                                         RatingsDatabase db) throws SQLException {
        Integer siteId = db.getSiteId(url);
        System.out.println("Adding links to DB from " + url);
        if (siteId != null) {
            db.insertRowsInPagesTable(links, siteId, null);
        }
    }

    /**
     *
     * @param url   Page url in db, by
     *              which we can get site id.
     * @param links Page urls with
     *              creation timestamps.
     * @param db    database to save the links
     * @throws SQLException
     */
    private void saveLinksToDb(String url, Map<String, Timestamp> links,
                                      RatingsDatabase db) throws SQLException {
        Integer siteId = db.getSiteId(url);
        System.out.println("Adding links to DB from " + url);
        if (siteId != null) {
            db.insertRowsInPagesTable(links, siteId, null);
        }
    }

    /**
     * Inserts to db all page ranks
     * from index for persons in db.
     *
     * @param ratingsDb
     * @param index
     * @throws SQLException
     */
    private void updateAllPersonsPageRanks(RatingsDatabase ratingsDb, Index index) throws SQLException {
        Map<Integer, Map<String, Integer>> personPageRanks = getPageRanksFromIndex(ratingsDb, index);
        for (Integer personId : personPageRanks.keySet()) {
            Map<String, Integer> ranks = personPageRanks.get(personId);
            ratingsDb.insertPersonPageRanks(personId, ranks);
        }
    }

    /**
     * Gets all page ranks for
     * persons in db from index.
     *
     * @param ratingsDb database, containing persons
     * @param index     index, containing page ranks
     * @return
     * @throws SQLException
     */
    private Map<Integer, Map<String, Integer>> getPageRanksFromIndex(
            RatingsDatabase ratingsDb, Index index) throws SQLException {
        Map<Integer, Set<String>> keywords = ratingsDb.getPersonsWithKeywords();
        Map<Integer, Map<String, Integer>> pageRanks = new HashMap<>();
        for (Integer personId: keywords.keySet()) {
            Map<String, Integer> personPageRanks = new HashMap<>();
            System.out.println();
            for (String word : keywords.get(personId)) {
                System.out.println("Getting counts for word: " + word);
                Map<String, Integer> counts = index.getCounts(word.toLowerCase());
                putOrAdd(counts, personPageRanks);
            }
            System.out.println("\nAll page ranks for person with ID = " + personId);
            for (String url : personPageRanks.keySet()) {
                System.out.println(url + " : " + personPageRanks.get(url));
            }
            pageRanks.put(personId, personPageRanks);
        }
        return pageRanks;
    }

    /**
     * Inserts values from source map
     * to target, or only adds values
     * if such keys are already present.
     *
     * @param source
     * @param target
     */
    private void putOrAdd(Map<String, Integer> source, Map<String, Integer> target) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String key = entry.getKey();
            Integer value = entry.getValue();
            target.merge(key, value, (first, second) -> first + second);
        }
    }

    /**
     * Runs this object methods,
     * according to the input args.
     *
     * @param args
     * @throws Exception
     */
    private void parseInput(String[] args) throws Exception {
        if (args.length > 0) {
            for (String arg : args) {
                if (arg.contains("-rdx"))
                    reindexPageRanks(dbFactory.getRatingsDb(), dbFactory.getIndex());
                if (arg.contains("-irl"))
                    insertLinksToRobotsPages(dbFactory.getRatingsDb());
                if (arg.contains("-frl"))
                    fetchLinksFromRobotsTxt(dbFactory.getRatingsDb());
                if (arg.contains("-fsl"))
                    fetchLinksFromSitemaps(dbFactory.getRatingsDb());
                if (arg.contains("-cul"))
                    crawlUnscannedPages(dbFactory.getRatingsDb(), dbFactory.getIndex());
                if (arg.contains("-all")) {
                    runWholeProgramCycle();
                }
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i].contains("-rtm")) {
                    try {
                        DBFactory.REDIS_TIMEOUT = Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException exc) {
                        System.out.println("Wrong number format for -rtm parameter!");
                    }
                }
                if (args[i].contains("-lpc")) {
                    try {
                        PAGES_PER_SCAN_CYCLE = Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException exc) {
                        System.out.println("Wrong number format for -lpc parameter!");
                    }
                }
            }
        } else {
            printUsage();
        }
    }

    private void printUsage() {
        System.out.println("Usage: java Crawler -<param>\n");
        System.out.println("List of available parameters:");
        System.out.println("-irl - insert links to robots.txt in database for found new sites;");
        System.out.println("-frl - fetch links from robots.txt's and save them to the database;");
        System.out.println("-fsl - fetch links from unscanned sitemaps, found in db and save them;");
        System.out.println("-cul - crawl unscanned pages, found in database, and save words from them;");
        System.out.println("-rdx - reindex persons page ranks from previously saved vocabularies;");
        System.out.println("-all - run whole cycle of crawling;");
        System.out.println("-rtm <number> - set redis time-out;");
        System.out.println("-lpc <number> - set number of links to process at a time.");
    }

    /**
     * Runs whole cycle of crawling.
     *
     * @throws Exception
     */
    private void runWholeProgramCycle() throws Exception {
        RatingsDatabase rdb = dbFactory.getRatingsDb();
        reindexPageRanks(rdb, dbFactory.getIndex());
        insertLinksToRobotsPages(rdb);
        fetchLinksFromRobotsTxt(rdb);
        fetchLinksFromSitemaps(rdb);
        crawlUnscannedPages(rdb, dbFactory.getIndex());
    }

    /**
     * Sets class constants according
     * to the property files content.
     *
     * @throws IOException
     */
    private void setProperties() throws IOException {
        String filename = MYSQL_PROPS_FILENAME;
        InputStream in = getClass().getResourceAsStream(MYSQL_PROPS_FILENAME);
        BufferedReader br = new BufferedReader(new InputStreamReader(in));

        try {
            DBFactory.MYSQL_ADRESS = br.readLine();
            DBFactory.MYSQL_USERNAME = br.readLine();
            DBFactory.MYSQL_PASSWORD = br.readLine();
            br.close();

            filename = REDIS_PROPS_FILENAME;
            in = getClass().getResourceAsStream(filename);
            br = new BufferedReader(new InputStreamReader(in));
            DBFactory.REDIS_HOST = br.readLine();
            DBFactory.REDIS_PORT = Integer.parseInt(br.readLine());
            br.close();
        } catch (IOException exc) {
            System.out.println("Can't read file! Filename: " + filename);
            throw exc;
        }
    }

    public static void main(String[] args) {
        try {
            DBFactory dbFactory = new DBFactory();
            WebCrawler wc = new WebCrawler(dbFactory);
            wc.parseInput(args);
//            URL url = new URL("https://kandidat.lenta.ru/#mainPage");
//            System.out.println(ArticleExtractor.INSTANCE.getText(url));
//            dbFactory.close();
        } catch (Exception exc) {
            System.out.println("The program was stopped abnormally.");
            exc.printStackTrace();
        }
    }
}

/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Allen Downey
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.andreyfadeev.crawler.dbs.redis;

import org.andreyfadeev.crawler.interfaces.Index;
import org.andreyfadeev.crawler.interfaces.TermContainer;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.util.*;

/**
 * Represents a Jedis implementation
 * of Redis-backed web search index.
 *
 * @author Allen Downey
 * @author Andrey Fadeev
 */
public class JedisIndex implements Index {

	private static final String COLON = ":";
	private static final String CRAWLER_PREFIX = "Crawler_";
	private static final String URLSET_PREFIX = "URLSet:";
	private static final String TERM_COUNTER_PREFIX = "TermCounter:";
	private static final String ASTERISK = "*";
	private Jedis jedis;

	/**
	 * Constructor.
	 *
	 * @param jedis
	 */
	public JedisIndex(Jedis jedis) {
		this.jedis = jedis;
	}

	/**
	 * Returns the Redis key for a given search term.
	 *
	 * @return Redis key.
	 */
	private String urlSetKey(String term) {
		return CRAWLER_PREFIX + URLSET_PREFIX + term;
	}

	/**
	 * Returns the Redis key for a URL's TermCounter.
	 *
	 * @return Redis key.
	 */
	private String termCounterKey(String url) {
		return CRAWLER_PREFIX + TERM_COUNTER_PREFIX + url;
	}

	@Override
	public boolean isIndexed(String url) {
		String redisKey = termCounterKey(url);
		return jedis.exists(redisKey);
	}

	@Override
	public void add(String term, String url) {
		jedis.sadd(urlSetKey(term), url);
	}

	@Override
	public Set<String> getURLs(String term) {
		return jedis.smembers(urlSetKey(term));
	}

	@Override
	public Map<String, Integer> getCounts(String term) {
		Map<String, Integer> map = new HashMap<>();
		Set<String> urls = getURLs(term);
		for (String url: urls) {
			Integer count = getCount(url, term);
			System.out.println(url + " : " + count);
			if (count != null) {
				map.put(url, count);
			}
		}
		return map;
	}

	@Override
	public @Nullable Integer getCount(String url, String term) {
		String redisKey = termCounterKey(url);
		String count = jedis.hget(redisKey, term);
		if (count != null) {
			return new Integer(count);
		} else {
			return null;
		}
	}

	@Override
	public List<String> putTerms(TermContainer tc) {
	    List<String> terms = new ArrayList<>();
		System.out.println("Putting terms in index: ");
		System.out.println(tc);

		String url = tc.getLabel();
		String hashName = termCounterKey(url);

		// if this page has already been indexed; delete the old hash
		jedis.del(hashName);

		// for each term, add an entry in the termcounter and a new
		// member of the index
		for (String term: tc.keySet()) {
			Integer count = tc.get(term);
			jedis.hset(hashName, term, count.toString());
			jedis.sadd(urlSetKey(term), url);
			terms.add(term);
		}
		return terms;
	}

	/**
	 * Adds vocabulary of page to the index.
	 *
	 * @param tc
	 * @return List of return values from Redis.
	 */
	public List<String> putTermsFaster(TermContainer tc) {
		System.out.println("Putting terms in index: ");
		System.out.println(tc);
		Transaction t = jedis.multi();

		String url = tc.getLabel();
		String hashName = termCounterKey(url);

		// if this page has already been indexed; delete the old hash
		t.del(hashName);

		// for each term, add an entry in the termcounter and a new
		// member of the index
		for (String term: tc.keySet()) {
			Integer count = tc.get(term);
			t.hset(hashName, term, count.toString());
			t.sadd(urlSetKey(term), url);
		}

		t.exec();
		return null;
	}

	/**
	 * Prints the contents of the index.
	 *
	 * Should be used for development and testing, not production.
	 */
	public void printIndex() {
		// loop through the search terms
		for (String term: termSet()) {
			System.out.println(term);

			// for each term, print the pages where it appears
			Set<String> urls = getURLs(term);
			for (String url: urls) {
				Integer count = getCount(url, term);
				System.out.println("    " + url + " " + count);
			}
		}
	}

	/**
	 * Returns the set of terms that have been indexed.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public Set<String> termSet() {
		Set<String> keys = urlSetKeys();
		Set<String> terms = new HashSet<>();
		for (String key: keys) {
			String[] array = key.split(COLON);
			if (array.length < 2) {
				terms.add("");
			} else {
				terms.add(array[1]);
			}
		}
		return terms;
	}

	/**
	 * Returns URLSet keys for the terms that have been indexed.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public Set<String> urlSetKeys() {
		return jedis.keys(urlSetKey(ASTERISK));
	}

	/**
	 * Returns TermCounter keys for the URLS that have been indexed.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public Set<String> termCounterKeys() {
		return jedis.keys(termCounterKey(ASTERISK));
	}

	/**
	 * Deletes all URLSet objects from the database.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public void deleteURLSets() {
		Set<String> keys = urlSetKeys();
		Transaction t = jedis.multi();
		for (String key: keys) {
			t.del(key);
		}
		t.exec();
	}

	/**
	 * Deletes all TermCounter objects from the database.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public void deleteTermCounters() {
		Set<String> keys = termCounterKeys();
		Transaction t = jedis.multi();
		for (String key: keys) {
			t.del(key);
		}
		t.exec();
	}

	@Override
	public void close() {
		jedis.close();
	}
}

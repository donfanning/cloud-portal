package de.papke.cloud.portal.service;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.util.ssl.SSLUtil;

import de.papke.cloud.portal.constants.Constants;
import de.papke.cloud.portal.pojo.User;

@Service
public class DirectoryService {

	private static final Logger LOG = LoggerFactory.getLogger(DirectoryService.class);
	
	private static final String SECURE_SCHEME = "ldaps";

	@Value("${LDAP_URL_STRING}")
	private String urlString;

	@Value("${LDAP_BASE_DN}")
	private String baseDn;

	@Value("${LDAP_PRINCIPAL}")
	private String principal;

	@Value("${LDAP_PASSWORD}")
	private String password;

	@Value("${LDAP_USER_SEARCH_FILTER}")
	private String userSearchFilter;

	@Value("${LDAP_LOGIN_ATTRIBUTE}")
	private String loginAttribute;

	@Value("${LDAP_DISPLAYNAME_ATTRIBUTE}")
	private String displayNameAttribute;
	
	@Value("${LDAP_GIVENNAME_ATTRIBUTE}")
	private String givenNameAttribute;

	@Value("${LDAP_SURNAME_ATTRIBUTE}")
	private String surNameAttribute;

	@Value("${LDAP_MAIL_ATTRIBUTE}")
	private String mailAttribute;

	@Value("${LDAP_GROUP_ATTRIBUTE}")
	private String groupAttribute;
	
	@Value("${LDAP_MEMBER_ATTRIBUTE}")
	private String memberAttribute;

	@Value("${LDAP_TIMEOUT}")
	private Integer timeout;

	@Value("${LDAP_PAGE_SIZE}")
	private Integer pageSize;

	private String[] urls;

	@PostConstruct
	public void init() {
		if (StringUtils.isNoneEmpty(urlString)) {
			urls = urlString.split(",");
		}
	}

	public boolean authenticate(String username, String password) {

		boolean success = false;
		LDAPConnection ldapConnection = null;
		String loginDn = getLoginDn(username);

		if (loginDn != null) {
			try {
				ldapConnection = getUserConnection(loginDn, password);
				if (ldapConnection != null) {
					success = true;
				}
			}
			catch (Exception e) {
				LOG.error(e.getMessage(), e);
			}
			finally {
				if (ldapConnection != null) {
					ldapConnection.close();
				}
			}
		}

		return success;
	}
	
	private String getNameFromDN(String dnString) throws LDAPException {
		return new DN(dnString).getRDN().getAttributeValues()[0];
	}

	private String getLoginDn(String username) {

		String loginDn = null;

		try {

			Filter userFilter = Filter.create(userSearchFilter);
			Filter loginFilter = getLoginFilter(username);
			Filter filter = Filter.createANDFilter(userFilter, loginFilter);

			List<SearchResultEntry> searchResultEntries = search(filter);
			if (!searchResultEntries.isEmpty()) {
				loginDn = searchResultEntries.get(0).getDN();
			}
		}
		catch (Exception e) {
			LOG.error(e.getMessage(), e);
		}

		return loginDn;
	}        

	private Filter getLoginFilter(String username) {
		return Filter.createEqualityFilter(loginAttribute, username);
	}
	
	private Filter getMemberFilter(String userDn) {
		return Filter.createEqualityFilter(memberAttribute, userDn);
	}

	private LDAPConnection getUserConnection(String principal, String password) {
		return getFailoverLdapConnection(principal, password);        
	}    

	private LDAPConnection getAdminConnection() {
		return getFailoverLdapConnection(principal, password);
	}    

	private LDAPConnection getFailoverLdapConnection(String principal, String password) {
		for (String url : urls) {
			LDAPConnection ldapConnection = getLdapConnection(principal, password, url.trim());
			if (ldapConnection != null) {
				return ldapConnection;
			}
		}
		return null;
	}   

	private LDAPConnection getLdapConnection(String principal, String password, String url) {

		LDAPConnection ldapConnection = null;

		try {
			LDAPURL ldapUrl = new LDAPURL(url);
			LDAPConnectionOptions ldapConnectionOptions = new LDAPConnectionOptions();
			ldapConnectionOptions.setConnectTimeoutMillis(timeout);

			SSLSocketFactory sslSocketFactory = null;
			if (ldapUrl.getScheme().equals(SECURE_SCHEME)) {
				SSLUtil sslUtil = new SSLUtil();
				sslSocketFactory = sslUtil.createSSLSocketFactory();
			}
			
			ldapConnection = new LDAPConnection(sslSocketFactory, ldapConnectionOptions, ldapUrl.getHost(), ldapUrl.getPort(), principal, password);
		}
		catch (Exception e) {
			LOG.error(e.getMessage(), e);
		}

		return ldapConnection;
	}    

	public List<SearchResultEntry> search(String filterString) throws LDAPException {
		return search(Filter.create(filterString), new String[]{});
	}

	public List<SearchResultEntry> search(Filter filter) {
		return search(filter, new String[]{});
	}

	public List<SearchResultEntry> search(String filterString, String[] attributes) throws LDAPException {
		return search(SearchScope.SUB, Filter.create(filterString), attributes);
	}

	public List<SearchResultEntry> search(Filter filter, String[] attributes) {
		return search(SearchScope.SUB, filter, attributes);
	}

	public List<SearchResultEntry> search(SearchScope searchScope, String filterString, String[] attributes) throws LDAPException {
		return search(baseDn, searchScope, Filter.create(filterString), attributes);
	}

	public List<SearchResultEntry> search(SearchScope searchScope, Filter filter, String[] attributes) {
		return search(baseDn, searchScope, filter, attributes);
	}

	public List<SearchResultEntry> search(String baseDn, SearchScope searchScope, String filterString, String[] attributes) throws LDAPException {
		return search(baseDn, searchScope, Filter.create(filterString), attributes, false);
	}

	public List<SearchResultEntry> search(String baseDn, SearchScope searchScope, Filter filter, String[] attributes) {
		return search(baseDn, searchScope, filter, attributes, false);
	}

	public List<SearchResultEntry> search(String baseDn, SearchScope searchScope, String filterString, String[] attributes, boolean paging) throws LDAPException {
		return search(baseDn, searchScope, Filter.create(filterString), attributes, paging);    
	}

	public List<SearchResultEntry> search(String baseDn, SearchScope searchScope, Filter filter, String[] attributes, boolean paging) {

		List<SearchResultEntry> searchResultEntries = new ArrayList<>();

		try {

			LDAPConnection connection = getAdminConnection();

			if (connection != null) { 

				// check if paging should be used
				if (paging) {

					// create LDAP search request
					SearchRequest searchRequest = new SearchRequest(baseDn, searchScope, filter, attributes);

					// instantiate variable for paging cookie
					ASN1OctetString cookie = null;

					do {

						// set controls for LDAP search request
						Control[] controls = new Control[1];
						controls[0] = new SimplePagedResultsControl(pageSize, cookie);
						searchRequest.setControls(controls);

						// execute LDAP search request
						SearchResult searchResult = connection.search(searchRequest);

						// add search entries from page to result list
						searchResultEntries.addAll(searchResult.getSearchEntries());

						// get cookie for next page
						cookie = null;
						for (Control control : searchResult.getResponseControls()) {
							if (control instanceof SimplePagedResultsControl) {
								SimplePagedResultsControl simplePagedResultsControl = (SimplePagedResultsControl) control; 
								cookie = simplePagedResultsControl.getCookie();
							}
						}

					} 
					// do this as long as a cookie is returned
					while ((cookie != null) && (cookie.getValueLength() > 0));
				}
				else {
					// execute LDAP search request
					SearchResult searchResult = connection.search(baseDn, searchScope, filter, attributes);

					// set search entries as result list
					searchResultEntries = searchResult.getSearchEntries();
				}
			}
		}
		catch (Exception e) {
			LOG.error(e.getMessage(), e);
		}

		return searchResultEntries;
	}    
	
	public User getUser(String username) {

		User user = new User();

		try {

			List<SearchResultEntry> userEntryList = search(getLoginFilter(username));
			if (!userEntryList.isEmpty()) {

				SearchResultEntry userEntry = userEntryList.get(0);

				String givenName = null;
				String surName = null;
				
				if (StringUtils.isNotEmpty(displayNameAttribute)) {
					
					StringBuilder givenNameBuilder = new StringBuilder();
					StringBuilder surNameBuilder = new StringBuilder();
					
					String displayName = userEntry.getAttributeValue(displayNameAttribute);
					String[] displayNameArray = displayName.split(Constants.CHAR_WHITESPACE);
					
					for (int i = 0; i < displayNameArray.length; i++) {
						if (i < displayNameArray.length - 1) {
							givenNameBuilder.append(displayNameArray[i]);
						}
						else {
							surNameBuilder.append(displayNameArray[i]);
						}
					}
					
					givenName = givenNameBuilder.toString();
					surName = surNameBuilder.toString();
				}
				else {
					givenName = userEntry.getAttributeValue(givenNameAttribute);
					surName = userEntry.getAttributeValue(surNameAttribute);
				}
				
				user.setUsername(username);
				user.setGivenName(givenName);
				user.setSurName(surName);
				user.setEmail(userEntry.getAttributeValue(mailAttribute));
				
				List<String> groups = new ArrayList<>();        
				if (userEntry.hasAttribute(groupAttribute)) {
					String[] groupAttributeValues = userEntry.getAttributeValues(groupAttribute);
					for (String groupAttributeValue : groupAttributeValues) {
						groups.add(getNameFromDN(groupAttributeValue));
					}
				}
				else {
					List<SearchResultEntry> groupEntryList = search(getMemberFilter(userEntry.getDN()));
					for (SearchResultEntry groupEntry : groupEntryList) {
						groups.add(getNameFromDN(groupEntry.getDN()));
					}
				}
				
				user.setGroups(groups);
			}
		}
		catch(Exception e) {
			LOG.error(e.getMessage(), e);
		}

		return user;
	}
}

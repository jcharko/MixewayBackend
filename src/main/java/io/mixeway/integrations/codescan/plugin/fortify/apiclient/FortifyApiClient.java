package io.mixeway.integrations.codescan.plugin.fortify.apiclient;

import com.google.gson.JsonObject;
import io.mixeway.config.Constants;
import io.mixeway.db.entity.Scanner;
import io.mixeway.db.entity.Vulnerability;
import io.mixeway.db.entity.*;
import io.mixeway.db.repository.*;
import io.mixeway.domain.service.vulnerability.VulnTemplate;
import io.mixeway.integrations.bugtracker.BugTracking;
import io.mixeway.integrations.codescan.model.CodeRequestHelper;
import io.mixeway.integrations.codescan.model.TokenValidator;
import io.mixeway.integrations.codescan.plugin.fortify.model.*;
import io.mixeway.integrations.codescan.service.CodeScanClient;
import io.mixeway.pojo.*;
import io.mixeway.rest.model.ScannerModel;
import io.mixeway.rest.project.model.SASTProject;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.ProtocolException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class FortifyApiClient implements CodeScanClient, SecurityScanner {
	private static final Logger log = LoggerFactory.getLogger(FortifyApiClient.class);
	private final VaultHelper vaultHelper;
	private final ScannerRepository scannerRepository;
	private final CodeProjectRepository codeProjectRepository;
	private final CodeGroupRepository codeGroupRepository;
	private final FortifySingleAppRepository fortifySingleAppRepository;
	private final StatusRepository statusRepository;
	private final SecureRestTemplate secureRestTemplate;
	private final ScannerTypeRepository scannerTypeRepository;
	private final BugTrackerRepository bugTrackerRepository;
	private final List<BugTracking> bugTrackings ;
	private final CiOperationsRepository ciOperationsRepository;
	private final VulnTemplate vulnTemplate;
	private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
	private final SimpleDateFormat sdfForFortify = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	private final String[] blackListedLocation = new String[] {"src","source","vendor","lib" };


	FortifyApiClient(VaultHelper vaultHelper, ScannerRepository scannerRepository, VulnTemplate vulnTemplate, List<BugTracking> bugTrackings,
					 CodeProjectRepository codeProjectRepository, CodeGroupRepository codeGroupRepository, FortifySingleAppRepository fortifySingleAppRepository,
					 StatusRepository statusRepository, SecureRestTemplate secureRestTemplate, ScannerTypeRepository scannerTypeRepository,
					 BugTrackerRepository bugTrackerRepository, CiOperationsRepository ciOperationsRepository){
		this.vaultHelper = vaultHelper;
		this.bugTrackerRepository = bugTrackerRepository;
		this.scannerRepository = scannerRepository;
		this.codeProjectRepository = codeProjectRepository;
		this.bugTrackings = bugTrackings;
		this.vulnTemplate = vulnTemplate;
		this.codeGroupRepository = codeGroupRepository;
		this.fortifySingleAppRepository = fortifySingleAppRepository;
		this.statusRepository = statusRepository;
		this.secureRestTemplate = secureRestTemplate;
		this.scannerTypeRepository = scannerTypeRepository;
		this.ciOperationsRepository = ciOperationsRepository;
	}

	private final JsonObject unifiedTokenObject = new JsonObject();
	private final TokenValidator tokenValidator = new TokenValidator();

	//SSC Generation
	@Override
	public boolean initialize(io.mixeway.db.entity.Scanner scanner) throws JSONException, ParseException, CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException {
		if (scanner.getScannerType().getName().equals(Constants.SCANNER_TYPE_FORTIFY)) {
			return generateToken(scanner);
		} else if (scanner.getScannerType().getName().equals(Constants.SCANNER_TYPE_FORTIFY_SCA)){
			try {
				RestTemplate restTemplate = secureRestTemplate.prepareClientWithCertificate(null);
				ResponseEntity<String> response = restTemplate.exchange(scanner.getApiUrl()+"/initialize", HttpMethod.GET, null, String.class);
				if (response.getStatusCode().equals(HttpStatus.OK)) {
					scanner.setStatus(true);
					scannerRepository.save(scanner);
					return true;
				}
			} catch (ProtocolException e) {
				log.error("Exception occured during initialization of scanner: '{}'",e.getMessage());
			}
			return false;
		} else {
			return false;
		}
	}
	private boolean generateToken(io.mixeway.db.entity.Scanner scanner) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException, JSONException, ParseException {
		unifiedTokenObject.addProperty("type", "UnifiedLoginToken");
		RestTemplate restTemplate = secureRestTemplate.prepareClientWithCertificate(scanner);
		final String passwordToEncode = scanner.getUsername() + ":" + vaultHelper.getPassword(scanner.getPassword());
		final byte[] passwordToEncodeBytes = passwordToEncode.getBytes(StandardCharsets.UTF_8);
		HttpHeaders headers = new HttpHeaders();
		headers.set("Content-Type", "application/json");
		headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(passwordToEncodeBytes));
		HttpEntity<String> entity = new HttpEntity<>(unifiedTokenObject.toString(), headers);
		String API_GET_TOKEN = "/api/v1/tokens";
		ResponseEntity<String> response = restTemplate.exchange(scanner.getApiUrl() + API_GET_TOKEN, HttpMethod.POST, entity, String.class);
		if (response.getStatusCode() == HttpStatus.CREATED) {
			JSONObject responseJson = new JSONObject(Objects.requireNonNull(response.getBody()));
			scanner.setFortifytoken(responseJson.getJSONObject("data").getString("token"));
			String date = responseJson.getJSONObject("data").getString("terminalDate");
			Date expiration = sdfForFortify.parse(date);
			scanner.setFortifytokenexpiration(sdf.format(expiration));
			if(!scanner.getStatus()){
				scanner.setStatus(true);
			}
			scannerRepository.save(scanner);
			return true;
		} else {
			log.error("Fortify Authorization failure");
			return false;
		}
	}

	//SSC Loading Vulnerabilities
	@Override
	public void loadVulnerabilities(io.mixeway.db.entity.Scanner scanner, CodeGroup codeGroup, String urlToGetNext, Boolean single, CodeProject codeProject, List<ProjectVulnerability> codeVulns) throws URISyntaxException, ParseException, JSONException {
		try {
			CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
			String url;
			String API_DOWNLOAD_ISSUES = "/api/v1/projectVersions/versionid/issues?qm=issues&q=[fortify+priority+order]:high+OR+[fortify+priority+order]:critical";
			if (single) {
				url = scanner.getApiUrl() + API_DOWNLOAD_ISSUES.replace("versionid",
						String.valueOf(codeGroup.getVersionIdsingle()>0?codeGroup.getVersionIdsingle():codeGroup.getVersionIdAll()));
			} else {
				url = scanner.getApiUrl() + API_DOWNLOAD_ISSUES.replace("versionid",
						String.valueOf(codeGroup.getVersionIdAll()));
			}
			if (urlToGetNext != null)
				url = url+"&"+urlToGetNext.split("&")[2];
			ResponseEntity<FortifyVulnList> response = codeRequestHelper
					.getRestTemplate()
					.exchange(url, HttpMethod.GET, codeRequestHelper.getHttpEntity(), FortifyVulnList.class);
			if (response.getStatusCode() == HttpStatus.OK) {
				saveVulnerabilities(codeGroup, Objects.requireNonNull(response.getBody()).getData(),codeProject,scanner, codeVulns);
				if (response.getBody().getLinks().getNext() != null ){
					this.loadVulnerabilities(scanner,codeGroup,response.getBody().getLinks().getNext().getHref(),single,codeProject,codeVulns);
				}
			} else {
				log.error("Fortify Authorization failure");
			}
			if (codeVulns !=null && codeProject != null) {
				log.info("Contains old vulns, reimporting");
				reimportAnalysisFromScans(codeProject,codeGroup, codeVulns);
			}
		} catch (ResourceAccessException | HttpClientErrorException | CertificateException | UnrecoverableKeyException | NoSuchAlgorithmException | KeyManagementException | KeyStoreException | IOException | HttpServerErrorException hcee){
			log.error("FortifySSC HttpClientErrorExceptio was unsuccessfull with code of: {} {} ",hcee.getLocalizedMessage(),hcee.getMessage());
		}
	}

	//For single project reimporting analysis
	private void reimportAnalysisFromScans(CodeProject codeProject, CodeGroup codeGroup, List<ProjectVulnerability> oldVulns) {
		List<ProjectVulnerability> codeVulns = new ArrayList<>();
		if (codeProject !=null ) {
			codeVulns = vulnTemplate.projectVulnerabilityRepository.findByCodeProject(codeProject);
		} else if (codeGroup!=null) {
			codeVulns = vulnTemplate.projectVulnerabilityRepository.findByCodeProjectIn(new ArrayList<>(codeGroup.getProjects()));
		}
		for (ProjectVulnerability cv : codeVulns){
			try {
				Optional<ProjectVulnerability> x = oldVulns.stream().filter(c -> c.getExternalId() == cv.getExternalId()).findFirst();
				if (x.isPresent()) {
					cv.setAnalysis(x.get().getAnalysis());
					cv.setTicketId(x.get().getTicketId());
					cv.setStatus(vulnTemplate.STATUS_EXISTING);
					cv.setGrade(x.get().getGrade());
				} else {
					cv.setStatus(vulnTemplate.STATUS_NEW);
					//TODO AUto Jira creation
				}
				vulnTemplate.projectVulnerabilityRepository.save(cv);
			} catch (NullPointerException ignored) {}
		}
	}
	private void saveVulnerabilities(CodeGroup codeGroup, List<FortifyVuln> fortifyVulns, CodeProject cp, io.mixeway.db.entity.Scanner scanner, List<ProjectVulnerability> oldVulns) throws JSONException, CertificateException, ParseException, NoSuchAlgorithmException, KeyManagementException, UnrecoverableKeyException, KeyStoreException, IOException, URISyntaxException {
		List<ProjectVulnerability> vulnsToPersist = new ArrayList<>();
		for (FortifyVuln fortifyVuln: fortifyVulns) {
			if (cp == null){
				cp = setCodeProjectForScan(codeGroup,cp,fortifyVuln);
			}
			Vulnerability vulnerability = vulnTemplate.createOrGetVulnerabilityService.createOrGetVulnerability(fortifyVuln.getIssueName());

			ProjectVulnerability projectVulnerability = new ProjectVulnerability(cp,cp,vulnerability,null,null,
					fortifyVuln.getFriority(),null,fortifyVuln.getFullFileName()+":"+fortifyVuln.getLineNumber(),fortifyVuln.getPrimaryTag(), vulnTemplate.SOURCE_SOURCECODE );


			createDescriptionAndState(fortifyVuln.getIssueInstanceId(), fortifyVuln.getId(),
					codeGroup.getVersionIdAll(), scanner, projectVulnerability);
			vulnsToPersist.add(projectVulnerability);
			//vulnTemplate.vulnerabilityPersist(oldVulns, projectVulnerability);
		}
		vulnTemplate.vulnerabilityPersistList(oldVulns, vulnsToPersist);

	}

	private CodeProject setCodeProjectForScan(CodeGroup codeGroup, CodeProject cp, FortifyVuln fortifyVuln) {
		if (cp != null){
			return cp;
		} else {
			if (!codeGroup.getHasProjects() && codeGroup.getProjects().size() == 0){
				return createCodeProjectForSignleCodeGroup(codeGroup);
			} else if (!codeGroup.getHasProjects() && codeGroup.getProjects().size() == 1){
				return codeGroup.getProjects().stream().findFirst().orElse(null);
			} else {
				return getProjectFromPath(codeGroup,fortifyVuln.getFullFileName());
			}
		}
	}

	private CodeProject createCodeProjectForSignleCodeGroup(CodeGroup codeGroup) {
		Optional<CodeProject> optionalCodeProject = codeProjectRepository.findByCodeGroupAndName(codeGroup,codeGroup.getName());
		if (!optionalCodeProject.isPresent()) {
			CodeProject cp = new CodeProject();
			cp.setName(codeGroup.getName());
			cp.setSkipAllScan(true);
			cp.setCodeGroup(codeGroup);
			cp.setTechnique(codeGroup.getTechnique());
			return codeProjectRepository.save(cp);
		} else
			return optionalCodeProject.get();
	}

	private ProjectVulnerability createDescriptionAndState(String instanceId, Long id, int versionid, io.mixeway.db.entity.Scanner scanner, ProjectVulnerability codeVuln) throws ParseException, CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, IOException, URISyntaxException {
		codeVuln.setExternalId(Math.toIntExact((id)));
		CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
		ResponseEntity<IssueDetailDataModel> response = codeRequestHelper
				.getRestTemplate()
				.exchange(scanner.getApiUrl()+"/api/v1/issueDetails/"+id, HttpMethod.GET, codeRequestHelper.getHttpEntity(), IssueDetailDataModel.class);
		if (response.getStatusCode() == HttpStatus.OK) {
			Vulnerability vulnerability = codeVuln.getVulnerability();
			vulnerability.setRefs(Objects.requireNonNull(response.getBody()).getIssueDetailModel().getReferences());
			vulnerability.setRecommendation(response.getBody().getIssueDetailModel().getRecommendation());
			vulnTemplate.vulnerabilityRepository.save(vulnerability);
			codeVuln.setDescription("Full Description here: https://fortifyssc.corpnet.pl/ssc/html/ssc/version/" + versionid + "/fix/" + id + "/?engineType=SCA&issue=" + instanceId + "&filterSet=a243b195-0a59-3f8b-1403-d55b7a7d78e6\n\n\n" +
					"Details: " + response.getBody().getIssueDetailModel().getDetail() );
			if (response.getBody().getIssueDetailModel().getScanStatus().equals(Constants.FORTIFY_ISSUE_STATE_UPDATED)){
				codeVuln.setStatus(statusRepository.findByName(Constants.STATUS_EXISTING));
			} else {
				codeVuln.setStatus(statusRepository.findByName(Constants.STATUS_NEW));
				processIssueTracking(codeVuln);
			}
		}

		return codeVuln;
	}

	private void processIssueTracking(ProjectVulnerability codeVuln) throws URISyntaxException {
		if (codeVuln.getCodeProject()!=null) {
			Optional<BugTracker> bugTracker = bugTrackerRepository.findByProjectAndVulns(codeVuln.getProject(), Constants.VULN_JIRA_CODE);
			if (bugTracker.isPresent() && codeVuln.getTicketId() == 0) {
				for (BugTracking bugTracking : bugTrackings) {
					if (bugTracking.canProcessRequest(bugTracker.get())) {
						bugTracking.processRequest(vulnTemplate.projectVulnerabilityRepository, Optional.of(codeVuln), bugTracker.get(), codeVuln.getProject(), Constants.VULN_JIRA_CODE, Constants.SCAN_MODE_AUTO, false);
					}
				}
			}
		}
	}
	private String getCodeSnippet(io.mixeway.db.entity.Scanner scanner, int versionid, String fullFileName, int lineNumber) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException, JSONException, ParseException {
		String codeSnippet = "";
		CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
		ResponseEntity<FileContentDataModel> response = codeRequestHelper
				.getRestTemplate()
				.exchange(scanner.getApiUrl()+"/api/v1/projectVersions/"+versionid+"/sourceFiles?q=path:\""
				+fullFileName+"\"", HttpMethod.GET, codeRequestHelper.getHttpEntity(), FileContentDataModel.class);
		if (response.getStatusCode() == HttpStatus.OK) {
			List<String> lines = new BufferedReader(new StringReader(Objects.requireNonNull(response.getBody()).getFileContentModel().get(0).getFileContent()))
					.lines()
					.collect(Collectors.toList());

			codeSnippet = lines.stream().skip(lineNumber).limit(10).collect(Collectors.joining("\n"));
		}
		return codeSnippet;
	}

	private CodeProject getProjectFromPath(CodeGroup group, String string) {
		String projectName = string.split("/")[0];
		Optional<CodeProject> codeProject = codeProjectRepository.findByCodeGroupAndName(group, projectName);
		if(codeProject.isPresent())
			return codeProject.get();
		else if (Arrays.stream(blackListedLocation).noneMatch(projectName::equals)) {
			CodeProject codeProjectNew = new CodeProject();
			codeProjectNew.setCodeGroup(group);
			codeProjectNew.setSkipAllScan(true);
			codeProjectNew.setName(projectName);
			codeProjectRepository.save(codeProjectNew);
			log.info("Creating project {} for group {}", projectName,group.getName());
			return codeProjectNew;
		} else if(Arrays.asList(blackListedLocation).contains(projectName)) {
			if (!group.getHasProjects() && group.getProjects().size() == 0){
				return createCodeProjectForSignleCodeGroup(group);
			} else if (!group.getHasProjects() && group.getProjects().size() == 1){
				return group.getProjects().stream().findFirst().orElse(null);
			} else {
				Optional<CodeProject> optionalCodeProject = codeProjectRepository.findByCodeGroupAndName(group,group.getName());
				return optionalCodeProject.orElseGet(() -> createCodeProjectForSignleCodeGroup(group));
			}
		}
		return null;
	}
	//SSC - status of cloduscan job
	private boolean verifyCloudScanJob(CodeGroup cg) throws ParseException, CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, IOException {
		try {
			io.mixeway.db.entity.Scanner scanner = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY)).get(0);
			CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
			String API_JOB_STATE = "/api/v1/cloudjobs";
			ResponseEntity<CloudJobState> response = codeRequestHelper
					.getRestTemplate()
					.exchange(scanner.getApiUrl() + API_JOB_STATE + "/" + cg.getScanid(), HttpMethod.GET, codeRequestHelper.getHttpEntity(), CloudJobState.class);
			if (response.getStatusCode() == HttpStatus.OK) {
				if (Objects.requireNonNull(response.getBody()).getData().getJobState().equals(Constants.FORTIFY_UPLOAD_COMPLETED)) {
					log.info("CloudScan ended for {}", cg.getName());
					return true;
				} else if (response.getBody().getData().getJobState().equals(Constants.FORTIFY_SCAN_FOULTED) ||
						response.getBody().getData().getJobState().equals(Constants.FORTIFY_SCAN_FAILED) ||
						response.getBody().getData().getJobState().equals(Constants.FORTIFY_SCAN_CANCELED) ||
						response.getBody().getData().getJobState().equals(Constants.FORTIFY_UPLOAD_FAILED)) {
					cg.setRunning(false);
					cg.setRequestid(null);
					cg.setScanid(null);
					cg.setScope(null);
					codeGroupRepository.save(cg);
					updateRunningForCodeProjectsByCodeGroup(cg);
					log.info("CloudScan ended with FAULTED state for {}", cg.getName());
					return false;
				}
			}
			return false;
		} catch (HttpClientErrorException ex){
			log.debug("HttpClientErrorException during cloud scan job verification for {}",cg.getScanid());
		}
		return false;
	}

	private void updateRunningForCodeProjectsByCodeGroup(CodeGroup cg) {
		for (CodeProject codeProject : codeProjectRepository.findByCodeGroupAndRunning(cg,true)){
			codeProject.setRunning(false);
			codeProjectRepository.save(codeProject);
		}
	}

	private CodeRequestHelper prepareRestTemplate(io.mixeway.db.entity.Scanner scanner) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
		String dateToParse = scanner.getFortifytokenexpiration().split("\\.")[0];
		DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
		Date fortifyTokenExpirationDate = format.parse(dateToParse);
		//Date fortifyTokenExpirationDate = ldt;
		LocalDateTime fortifyTokenExpiration = LocalDateTime.ofInstant(fortifyTokenExpirationDate.toInstant(), ZoneId.systemDefault());
		if (tokenValidator.isTokenValid(scanner.getFortifytoken(), fortifyTokenExpiration)) {
			generateToken(scanner);
		}
		RestTemplate restTemplate = secureRestTemplate.prepareClientWithCertificate(scanner);
		HttpHeaders headers = new HttpHeaders();
		headers.set(Constants.HEADER_AUTHORIZATION, Constants.FORTIFY_TOKEN + " " + scanner.getFortifytoken());
		HttpEntity entity = new HttpEntity(headers);

		return new CodeRequestHelper(restTemplate,entity);
	}

	private CreateFortifyScanRequest prepareScanRequestForGroup(CodeGroup cg){
		List<io.mixeway.db.entity.Scanner> fortify = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCA));
		CreateFortifyScanRequest fortifyScanRequest = new CreateFortifyScanRequest();
		fortifyScanRequest.setCloudCtrlToken(vaultHelper.getPassword(fortify.get(0).getFortifytoken()));
		fortifyScanRequest.setGroupName(cg.getName());
		fortifyScanRequest.setUsername(cg.getRepoUsername());
		fortifyScanRequest.setSingle(false);
		fortifyScanRequest.setPassword(vaultHelper.getPassword(cg.getRepoPassword()));
		fortifyScanRequest.setVersionId(cg.getVersionIdAll());
		fortifyScanRequest.setProjects(prepareProjectCodeForGroup(cg));
		return fortifyScanRequest;
	}
	private List<ProjectCode> prepareProjectCodeForGroup(CodeGroup cg){
		List<ProjectCode> projectCodes = new ArrayList<>();
		for (CodeProject cp : cg.getProjects()){
			if (!cp.getSkipAllScan()) {
				ProjectCode pc = new ProjectCode();
				pc.setProjectName(cp.getName());
				pc.setdTrackUuid(cp.getdTrackUuid());
				pc.setBranch(cp.getBranch()!=null && !cp.getBranch().equals("") ? cp.getBranch() : Constants.CODE_DEFAULT_BRANCH);
				pc.setProjectRepoUrl(cp.getRepoUrl());
				pc.setTechnique(cp.getTechnique());
				pc.setParams(cp.getAdditionalPath());
				projectCodes.add(pc);
			}
		}
		return projectCodes;
	}
	private CreateFortifyScanRequest prepareScanRequestForProject(CodeProject cp){
		List<io.mixeway.db.entity.Scanner> fortify = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCA));
		if (fortify.size()>0) {
			CreateFortifyScanRequest fortifyScanRequest = new CreateFortifyScanRequest();
			fortifyScanRequest.setCloudCtrlToken(vaultHelper.getPassword(fortify.get(0).getFortifytoken()));
			fortifyScanRequest.setGroupName(cp.getCodeGroup().getName());
			fortifyScanRequest.setSingle(true);
			fortifyScanRequest.setdTrackUuid(cp.getdTrackUuid());
			fortifyScanRequest.setUsername(cp.getCodeGroup().getRepoUsername());
			fortifyScanRequest.setPassword(vaultHelper.getPassword(cp.getCodeGroup().getRepoPassword()));
			fortifyScanRequest.setVersionId(0);
			ProjectCode pc = new ProjectCode();
			pc.setTechnique(cp.getTechnique());
			pc.setdTrackUuid(cp.getdTrackUuid());
			pc.setBranch(cp.getBranch() != null && !cp.getBranch().equals("") ? cp.getBranch() : Constants.CODE_DEFAULT_BRANCH);
			pc.setProjectRepoUrl(cp.getRepoUrl());
			pc.setProjectName(cp.getName());
			fortifyScanRequest.setProjects(Collections.singletonList(pc));
			return fortifyScanRequest;
		} else
			return null;
	}
	@Override
	public boolean isScanDone(CodeGroup cg, CodeProject cp) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException, ParseException, JSONException {
		if ((cg != null && StringUtils.isNotBlank(cg.getScanid())) || (cp !=null && StringUtils.isNotBlank(cp.getCodeGroup().getScanid()))){
			return verifyCloudScanJob(cg != null? cg : cp.getCodeGroup());
		} else {
			if (cp == null && cg!= null && getScanIdForCodeGroup(cg) && verifyCloudScanJob(cg)) {
				return true;
			} else return cg == null && cp != null && getScanIdForCodeProject(cp) && verifyCloudScanJob(cp.getCodeGroup());
		}
	}

	public boolean getScanIdForCodeProject(CodeProject cp) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException {
		List<io.mixeway.db.entity.Scanner> fortify = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCA));
		RestTemplate restTemplate = secureRestTemplate.prepareClientWithCertificate(null);
		ResponseEntity<FortifyScan> response = restTemplate.exchange(fortify.get(0).getApiUrl()+"/check/"+cp.getRequestId(), HttpMethod.GET, null, FortifyScan.class);

		if (response.getStatusCode().equals(HttpStatus.OK)) {
			if (Objects.requireNonNull(response.getBody()).getError() != null && response.getBody().getError()) {
				cp.setRunning(false);
				codeProjectRepository.save(cp);
				return false;
			} else if (response.getBody().getScanId() != null && response.getBody().getCommitid()!=null) {
				cp.setCommitid(response.getBody().getCommitid());
				codeProjectRepository.save(cp);
				createCiOperation(cp, response.getBody().getCommitid());
				updateScanIdForCodeGrorup(cp.getCodeGroup(), response.getBody().getScanId());
				log.info("Fortify scan was passed to cloudscan for [scope {}] {}", cp.getName(), cp.getCodeGroup().getName());
				return true;
			}
		}
		return false;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void updateScanIdForCodeGrorup(CodeGroup codeGroup, String scanId) {
		codeGroupRepository.runUpdateScanGroupToSetScanId(codeGroup.getId(), scanId);
	}
	private boolean getScanIdForCodeGroup(CodeGroup cg) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException {
		List<io.mixeway.db.entity.Scanner> fortify = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCA));
		RestTemplate restTemplate = secureRestTemplate.prepareClientWithCertificate(null);
		ResponseEntity<FortifyScan> response = restTemplate.exchange(fortify.get(0).getApiUrl()+"/check/"+cg.getRequestid(), HttpMethod.GET, null, FortifyScan.class);

		if (response.getStatusCode().equals(HttpStatus.OK)) {
			if (Objects.requireNonNull(response.getBody()).getError() != null && response.getBody().getError()) {
				cg.setRunning(false);
				codeGroupRepository.save(cg);
				return false;
			} else if (response.getBody().getScanId() != null) {
				cg.setScanid(response.getBody().getScanId());
				codeGroupRepository.save(cg);
				log.info("Fortify scan was passed to cloudscan for [scope {}] {} ", cg.getScope(), cg.getName());
				return true;
			}
		}
		return false;

	}

	@Override
	public boolean canProcessRequest(CodeGroup cg) {
		Optional<io.mixeway.db.entity.Scanner> fortify = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCC)).stream().findFirst();
		return fortify.isPresent();
	}

	@Override
	public boolean canProcessRequest(Scanner scanner) {
		return (scanner.getScannerType().getName().equals(Constants.SCANNER_TYPE_FORTIFY_SCA) || scanner.getScannerType().getName().equals(Constants.SCANNER_TYPE_FORTIFY)) && scanner.getStatus();
	}

	@Override
	public boolean canProcessInitRequest(Scanner scanner) {
		return (scanner.getScannerType().getName().equals(Constants.SCANNER_TYPE_FORTIFY_SCA) || scanner.getScannerType().getName().equals(Constants.SCANNER_TYPE_FORTIFY));
	}

	@Override
	public List<SASTProject> getProjects(Scanner scanner) {
		List<SASTProject> sastProjects = new ArrayList<>();
		try {
			CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);

			String API_GET_VERSIONS = "/api/v1/projectVersions";
			ResponseEntity<FortifyProjectVersionDto> response = codeRequestHelper
					.getRestTemplate()
					.exchange(scanner.getApiUrl() + API_GET_VERSIONS, HttpMethod.GET, codeRequestHelper.getHttpEntity(), FortifyProjectVersionDto.class);
			if (response.getStatusCode() == HttpStatus.OK) {
				for (FortifyProjectVersions fpv : Objects.requireNonNull(response.getBody()).getFortifyProjectVersions()) {
					SASTProject sastProject = new SASTProject(fpv.getId(), fpv.getProject().getName() + " - " + fpv.getName());
					sastProjects.add(sastProject);
				}
			}
		} catch (Exception e) {
			log.error("Exception came up during getting Fortify SSC projects");
		}
		return sastProjects;
	}

	@Override
	public boolean createProject(Scanner scanner, CodeProject codeProject) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
		try {
			CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
			HttpEntity<FortifyProjectVersions> entity = new HttpEntity<>(new FortifyProjectVersions(codeProject, scanner), codeRequestHelper.getHttpEntity().getHeaders());
			String API_GET_VERSIONS = "/api/v1/projectVersions";
			ResponseEntity<FortifyCreateProjectResponse> response = codeRequestHelper
					.getRestTemplate()
					.exchange(scanner.getApiUrl() + API_GET_VERSIONS, HttpMethod.POST, entity, FortifyCreateProjectResponse.class);
			if (response.getStatusCode() == HttpStatus.CREATED &&
					fortifyCreateAttributes(scanner,codeProject, response.getBody().getFortifyProjectVersions().getId()) &&
					fortifyCommitProject(scanner, codeProject, response.getBody().getFortifyProjectVersions().getId())) {
				codeProject.getCodeGroup().setVersionIdAll(response.getBody().getFortifyProjectVersions().getId());
				codeGroupRepository.save(codeProject.getCodeGroup());
				log.info("Successfully created Fortify SSC Project for {} with id {}", codeProject.getCodeGroup().getName(), codeProject.getCodeGroup().getVersionIdAll());
				return true;
			}
		} catch (HttpClientErrorException e){
			log.warn("Exception during FortifySSC project creation - {}", e.getLocalizedMessage());
		}
		return false;
	}

	@Override
	public void putInformationAboutScanFromRemote(CodeProject codeProject, CodeGroup codeGroup, String jobId) {
		codeGroup.setScope(codeProject.getName());
		codeGroup.setRunning(true);
		codeGroup.setScanid(jobId);
		codeGroup.setRequestid("xx");
		codeGroupRepository.saveAndFlush(codeGroup);
		codeProject.setRunning(true);
		codeProject.setRequestId("xx");
		codeProjectRepository.saveAndFlush(codeProject);
		FortifySingleApp fortifySingleApp = new FortifySingleApp();
		fortifySingleApp.setCodeGroup(codeGroup);
		fortifySingleApp.setCodeProject(codeProject);
		fortifySingleApp.setRequestId("XXX");
		fortifySingleApp.setJobToken(jobId);
		fortifySingleApp.setFinished(true);
		fortifySingleApp.setDownloaded(false);
		fortifySingleAppRepository.saveAndFlush(fortifySingleApp);
		log.info("Successfully put job {} from remote regarding {} / {}", LogUtil.prepare(jobId), LogUtil.prepare(codeGroup.getName()),LogUtil.prepare(codeProject.getName()));
	}

	private boolean fortifyCommitProject(Scanner scanner, CodeProject codeProject, int versionId) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
		try {
			CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
			HttpEntity<FortifyProjectVersions> entity = new HttpEntity<>(new FortifyProjectVersions(codeProject, scanner), codeRequestHelper.getHttpEntity().getHeaders());
			String API_GET_VERSIONS = "/api/v1/projectVersions/"+versionId;
			ResponseEntity<String> response = codeRequestHelper
					.getRestTemplate()
					.exchange(scanner.getApiUrl() + API_GET_VERSIONS, HttpMethod.PUT, entity, String.class);
			if (response.getStatusCode() == HttpStatus.OK ) {
				return true;
			}
		} catch (HttpClientErrorException e){
			log.warn("Exception during FortifySSC project creation - {}", e.getLocalizedMessage());
		}
		return false;
	}

	private boolean fortifyCreateAttributes(Scanner scanner, CodeProject codeProject, int versionId)throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, JSONException, KeyStoreException, ParseException, IOException {
		try {
			List<FortifyProjectAttributes> fortifyProjectAttributes = new ArrayList<>();
			fortifyProjectAttributes.add(new FortifyProjectAttributes("DevPhase",5,"New"));
			fortifyProjectAttributes.add(new FortifyProjectAttributes("DevPhase",6,"Internal"));
			fortifyProjectAttributes.add(new FortifyProjectAttributes("DevPhase",7,"internalnetwork"));
			fortifyProjectAttributes.add(new FortifyProjectAttributes("DevPhase",1,"High"));
			CodeRequestHelper codeRequestHelper = prepareRestTemplate(scanner);
			HttpEntity<List<FortifyProjectAttributes>> entity = new HttpEntity<>(fortifyProjectAttributes, codeRequestHelper.getHttpEntity().getHeaders());
			String API_GET_VERSIONS = "/api/v1/projectVersions/" + versionId + "/attributes";
			ResponseEntity<String> response = codeRequestHelper
					.getRestTemplate()
					.exchange(scanner.getApiUrl() + API_GET_VERSIONS, HttpMethod.PUT, entity, String.class);
			if (response.getStatusCode() == HttpStatus.OK ) {
				return true;
			}
		} catch (HttpClientErrorException e){
			log.warn("Exception during FortifySSC project creation - {}", e.getLocalizedMessage());
		}
		return false;
	}

	@Override
	public boolean canProcessRequest(ScannerType scannerType) {
		return scannerType.getName().equals(Constants.SCANNER_TYPE_FORTIFY) || scannerType.getName().equals(Constants.SCANNER_TYPE_FORTIFY_SCA);
	}

	@Override
	public Scanner saveScanner(ScannerModel scannerModel) throws Exception {
		List<Scanner>  scanners = scannerRepository.findByScannerTypeInAndStatus(scannerTypeRepository.getCodeScanners(), true);
		if (scanners.stream().findFirst().isPresent()){
			throw new Exception(Constants.SAST_SCANNER_ALREADY_REGISTERED);
		} else {
			ScannerType scannerType = scannerTypeRepository.findByNameIgnoreCase(scannerModel.getScannerType());
			if (scannerType.getName().equals(Constants.SCANNER_TYPE_FORTIFY)) {
				io.mixeway.db.entity.Scanner fortify = new io.mixeway.db.entity.Scanner();
				fortify.setApiUrl(scannerModel.getApiUrl());
				fortify.setUsername(scannerModel.getUsername());
				fortify.setStatus(false);
				fortify.setScannerType(scannerType);
				// api key put to vault
				String uuidToken = UUID.randomUUID().toString();
				if (vaultHelper.savePassword(scannerModel.getPassword(), uuidToken)){
					fortify.setPassword(uuidToken);
				} else {
					fortify.setPassword(scannerModel.getPassword());
				}
				return scannerRepository.save(fortify);
			} else if (scannerType.getName().equals(Constants.SCANNER_TYPE_FORTIFY_SCA)) {
				io.mixeway.db.entity.Scanner fortify = new io.mixeway.db.entity.Scanner();
				fortify.setApiUrl(scannerModel.getApiUrl());
				fortify.setStatus(false);
				fortify.setScannerType(scannerType);
				// api key put to vault
				String uuidToken = UUID.randomUUID().toString();
				if (vaultHelper.savePassword(scannerModel.getCloudCtrlToken(),uuidToken)){
					fortify.setFortifytoken(uuidToken);
				} else {
					fortify.setFortifytoken(scannerModel.getCloudCtrlToken());
				}
				return scannerRepository.save(fortify);
			}
		}
		return null;
	}

	private void getScanId(CodeGroup cg) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException {
		List<io.mixeway.db.entity.Scanner> fortify = scannerRepository.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCA));
		RestTemplate restTemplate = secureRestTemplate.prepareClientWithCertificate(null);
		ResponseEntity<FortifyScan> response = restTemplate.exchange(fortify.get(0).getApiUrl()+"/check/"+cg.getRequestid(), HttpMethod.GET, null, FortifyScan.class);

		if (response.getStatusCode().equals(HttpStatus.OK)) {
			if (Objects.requireNonNull(response.getBody()).getError() != null && response.getBody().getError()) {
				Optional<FortifySingleApp> fortifySingleApp = fortifySingleAppRepository.findByRequestId(response.getBody().getRequestId());
				if (fortifySingleApp.isPresent()) {
					fortifySingleApp.get().setFinished(true);
					fortifySingleApp.get().setDownloaded(true);
					fortifySingleAppRepository.save(fortifySingleApp.get());
				}
				cg.setRunning(false);
				codeGroupRepository.save(cg);
			} else if (response.getBody().getScanId() != null) {
				if (response.getBody().getProjectName() != null ) {
					Optional<CodeProject> cp = codeProjectRepository.findByCodeGroupAndName(cg, response.getBody().getProjectName());
					if (cp.isPresent()) {
						cp.get().setCommitid(response.getBody().getCommitid());
						createCiOperation(cp.get(), response.getBody().getCommitid());
						codeProjectRepository.save(cp.get());
						FortifySingleApp fortifySingleApp = new FortifySingleApp();
						fortifySingleApp.setCodeGroup(cg);
						fortifySingleApp.setCodeProject(cp.get());
						fortifySingleApp.setRequestId(response.getBody().getRequestId());
						fortifySingleApp.setJobToken(response.getBody().getScanId());
						fortifySingleApp.setFinished(false);
						fortifySingleApp.setDownloaded(false);
						fortifySingleAppRepository.saveAndFlush(fortifySingleApp);
						cp.get().setRunning(false);
						codeProjectRepository.saveAndFlush(cp.get());
						codeGroupRepository.saveAndFlush(cg);
					}
					//verifycloudscan for single
				} else {
					cg.setScanid(response.getBody().getScanId());
					codeGroupRepository.save(cg);
					//verifycloudscan for group
				}
				log.info("Fortify scan was passed to cloudscan for [scope {}] {} ", cg.getScope(), cg.getName());
			}
		}
	}

	private void createCiOperation(CodeProject codeProject, String commitid) {
		Optional<CiOperations> operation = ciOperationsRepository.findByCodeProjectAndCommitId(codeProject,commitid);
		if (!operation.isPresent() && StringUtils.isNotBlank(commitid)) {
			CiOperations newOperation = new CiOperations();
			newOperation.setProject(codeProject.getCodeGroup().getProject());
			newOperation.setCodeGroup(codeProject.getCodeGroup());
			newOperation.setCodeProject(codeProject);
			newOperation.setCommitId(commitid);
			ciOperationsRepository.save(newOperation);
			log.info("Creating CI Operation for {} - {} with commitid {}", newOperation.getProject().getName(), newOperation.getCodeProject().getName(), LogUtil.prepare(commitid));
		}
	}

	@Override
	public Boolean runScan(CodeGroup cg,CodeProject codeProject) throws CertificateException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, IOException {
		List<Scanner> fortify = scannerRepository
				.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCA));
		Optional<Scanner>fortifySSC = scannerRepository
				.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_FORTIFY_SCC)).stream().findFirst();
		Optional<Scanner> dTrack = scannerRepository
				.findByScannerType(scannerTypeRepository.findByNameIgnoreCase(Constants.SCANNER_TYPE_DEPENDENCYTRACK)).stream().findFirst();
		if (codeGroupRepository.countByRunning(true) ==0 && codeProjectRepository.findByRunning(true).size() ==0 && fortify.size()>0 && fortifySSC.isPresent()) {
			if (canRunScan(cg,codeProject)) {
				CreateFortifyScanRequest fortifyScanRequest;
				String scope;
				if (codeProject == null && cg != null) {
					fortifyScanRequest = prepareScanRequestForGroup(cg);
					scope = Constants.FORTIFY_SCOPE_ALL;
				} else {
					fortifyScanRequest = prepareScanRequestForProject(codeProject);
					scope = codeProject.getName();
				}
				fortifyScanRequest.setSscUrl(fortifySSC.get().getApiUrl());
				if (dTrack.isPresent()){
					fortifyScanRequest.setdTrackUrl(dTrack.get().getApiUrl());
					fortifyScanRequest.setdTrackToken(vaultHelper.getPassword(dTrack.get().getApiKey()));
				}
				try {
					RestTemplate restTemplate = secureRestTemplate.prepareClientWithCertificate(null);
					HttpHeaders headers = new HttpHeaders();
					headers.set("Content-Type", "application/json");
					HttpEntity<CreateFortifyScanRequest> entity = new HttpEntity<>(fortifyScanRequest, headers);
					ResponseEntity<FortifyScan> response = restTemplate.exchange(fortify.get(0).getApiUrl() + "/createscan", HttpMethod.PUT, entity, FortifyScan.class);
					if (response.getStatusCode().equals(HttpStatus.OK) && Objects.requireNonNull(response.getBody()).getRequestId() != null) {
						if (codeProject!=null){
							codeProject.setRunning(true);
							codeProject.setRequestId(response.getBody().getRequestId());
							codeProjectRepository.saveAndFlush(codeProject);
						} else {
							cg.setRequestid(response.getBody().getRequestId());
							cg.setRunning(true);
							cg.setScope(scope);
							codeGroupRepository.saveAndFlush(cg);
						}
						log.info("Fortify scan starged for [scope {}] {}",scope, cg.getName());
						return true;
					}
				} catch (ProtocolException e) {
					log.error("Exception occured during initialization of scanner: '{}'", e.getMessage());
				}
			} else {
				log.warn("Cannot start scan for {} because one is running with scope of {}", cg.getName(), cg.getScope());
			}
		} else {
			if (codeProject != null){
				log.info("There is already running scan [scope single] putting {} into queue[{}]", codeProject.getName(),cg.getName());
				codeProject.setInQueue(true);
				codeProjectRepository.save(codeProject);
			} else {
				log.info("There is already running scan [scope ALL], putting {} into queue", cg.getName());
				cg.setInQueue(true);
				codeGroupRepository.save(cg);
			}
		}
		return false;
	}

	private boolean canRunScan(CodeGroup cg, CodeProject codeProject) {
		if ( codeProject != null && codeProject.getRunning() )
			return false;
		else return cg == null || !cg.isRunning();
	}
}

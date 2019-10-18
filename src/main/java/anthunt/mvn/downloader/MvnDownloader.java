package anthunt.mvn.downloader;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.filter.ExclusionsDependencyFilter;

public class MvnDownloader {

	public static final String DEFAULT_TARGET_LOCAL_REPOSITORY = "target/local-repository";
	public static final String DEFAULT_MAVEN_REPOSITORY = "https://repo1.maven.org/maven2";
	public static final String DEFAULT_JAVA_SCOPE = JavaScopes.RUNTIME;
	
	private File pomFile;
	private String targetRepositoryPath;
	private String mvnRepositoryUrl;
	private String javaScope;
	private AbstractRepositoryListener repositoryListener;
	
	public MvnDownloader(File pomFile) {
		this(pomFile, null);
	}
	
	public MvnDownloader(File pomFile, AbstractRepositoryListener repositoryListener) {
		this(pomFile, DEFAULT_TARGET_LOCAL_REPOSITORY, DEFAULT_MAVEN_REPOSITORY, DEFAULT_JAVA_SCOPE, repositoryListener);
	}
	
	public MvnDownloader(File pomFile, String targetRepositoryPath, AbstractRepositoryListener repositoryListener) {
		this(pomFile, targetRepositoryPath, DEFAULT_MAVEN_REPOSITORY, DEFAULT_JAVA_SCOPE, repositoryListener);
	}
	
	public MvnDownloader(File pomFile, String targetRepositoryPath, String mvnRepositoryUrl, AbstractRepositoryListener repositoryListener) {
		this(pomFile, targetRepositoryPath, mvnRepositoryUrl, DEFAULT_JAVA_SCOPE, repositoryListener);
	}
	
	public MvnDownloader(File pomFile, String targetRepositoryPath, String mvnRepositoryUrl, String javaScope, AbstractRepositoryListener repositoryListener) {
		this.pomFile = pomFile;
		this.targetRepositoryPath = targetRepositoryPath;
		this.mvnRepositoryUrl = mvnRepositoryUrl;
		this.javaScope = javaScope;
		this.repositoryListener = repositoryListener;
	}
	
	public void getAllDependencies() throws ModelBuildingException, DependencyResolutionException {
		
		RepositorySystem repositorySystem = getRepositorySystem();
		RepositorySystemSession repositorySystemSession = getRepositorySystemSession(repositorySystem);
		
		List<RemoteRepository> remoteRepositories = getRepositories(repositorySystem, repositorySystemSession);
		
		final DefaultModelBuildingRequest modelBuildingRequest = new DefaultModelBuildingRequest().setPomFile(this.pomFile);
		
	    ModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
	    ModelBuildingResult modelBuildingResult = modelBuilder.build(modelBuildingRequest);
	
	    Model model = modelBuildingResult.getEffectiveModel();
	    for (Dependency d : model.getDependencies()) {
	        if(javaScope.equals(d.getScope())) {
	        	System.out.printf("processing dependency: %s, %s\n", d, d.getScope());
		        Artifact artifact = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getType(), d.getVersion());
		        
		        List<String> exclusionStrings = new ArrayList<String>();
		        List<Exclusion> exclusions = d.getExclusions();
		        for (Exclusion exclusion : exclusions) {
		        	exclusionStrings.add(
		        			new StringBuilder()
		        				.append(exclusion.getGroupId())
		        				.append(":")
		        				.append(exclusion.getArtifactId())
		        				.toString()
		        	);
		        }
		        
	        	CollectRequest collectRequest = new CollectRequest(new org.eclipse.aether.graph.Dependency(artifact, DEFAULT_JAVA_SCOPE), remoteRepositories);
	            DependencyFilter filter = DependencyFilterUtils.andFilter(
	            								DependencyFilterUtils.classpathFilter(DEFAULT_JAVA_SCOPE)
	            								, new ExclusionsDependencyFilter(exclusionStrings)
	            						  );
	            DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, filter);
	
	            repositorySystem.resolveDependencies(repositorySystemSession, dependencyRequest);
	        }
	
	    }
	}
	
	private RepositorySystem getRepositorySystem() {
	    DefaultServiceLocator serviceLocator = MavenRepositorySystemUtils.newServiceLocator();
	    serviceLocator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
	    serviceLocator.addService(TransporterFactory.class, FileTransporterFactory.class);
	    serviceLocator.addService(TransporterFactory.class, HttpTransporterFactory.class);
	
	    serviceLocator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
	        @Override
	        public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
	            System.err.printf("error creating service: %s\n", exception.getMessage());
	            exception.printStackTrace();
	        }
	    });
	
	    return serviceLocator.getService(RepositorySystem.class);
	}
	
	private DefaultRepositorySystemSession getRepositorySystemSession(RepositorySystem system) {
	    DefaultRepositorySystemSession repositorySystemSession = MavenRepositorySystemUtils.newSession();
	
	    LocalRepository localRepository = new LocalRepository(targetRepositoryPath);
	    repositorySystemSession.setLocalRepositoryManager(system.newLocalRepositoryManager(repositorySystemSession, localRepository));	
	    if(repositoryListener != null) repositorySystemSession.setRepositoryListener(repositoryListener);
	    return repositorySystemSession;
	}
	
	private List<RemoteRepository> getRepositories(RepositorySystem system, RepositorySystemSession session) {
	    return Arrays.asList(getCentralMavenRepository());
	}
	
	private RemoteRepository getCentralMavenRepository() {
	    RemoteRepository.Builder builder = new Builder("central", "default", mvnRepositoryUrl);
	    RemoteRepository central = builder.build();
	    return central;
	}
	
	public static void main(String[] args) {
		MvnDownloader mvnDownloader = new MvnDownloader(new File(args[0]), args[1], args[2], args[3], new AbstractRepositoryListener() {
		});
		try {
			mvnDownloader.getAllDependencies();
		} catch (DependencyResolutionException | ModelBuildingException e) {
			e.printStackTrace();
		}
	}
	
}

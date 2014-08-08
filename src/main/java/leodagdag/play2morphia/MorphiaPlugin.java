package leodagdag.play2morphia;

import com.mongodb.*;
import com.mongodb.gridfs.GridFS;

import leodagdag.play2morphia.utils.*;

import org.mongodb.morphia.AbstractEntityInterceptor;
import org.mongodb.morphia.Datastore;
import org.mongodb.morphia.Morphia;
import org.mongodb.morphia.ValidationExtension;
import org.mongodb.morphia.annotations.Embedded;
import org.mongodb.morphia.annotations.Entity;
import org.mongodb.morphia.mapping.Mapper;

import play.Application;
import play.Configuration;
import play.Logger;
import play.Play;
import play.Plugin;
import play.libs.Classpath;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MorphiaPlugin extends Plugin {

    private final Application application;

    private boolean isEnabled;

    private static Morphia morphia = null;

    private static MongoClient mongo = null;
    private static Datastore ds = null;
    private static GridFS gridfs;


    public MorphiaPlugin(Application application) {
        this.application = application;
    }

    @Override
    public void onStart() {
        if (!isEnabled) {
            return;
        }

        String dbName = null;
        String username = null;
        String password = null;    
        Configuration morphiaConf = null ;

        try {
            morphiaConf = Configuration.root().getConfig(ConfigKey.PREFIX);
            if (morphiaConf == null) {
                throw Configuration.root().reportError(ConfigKey.PREFIX, "Missing Morphia configuration", null);
            }

            MorphiaLogger.debug(morphiaConf);

            String mongoURIstr = morphiaConf.getString(ConfigKey.DB_MONGOURI.getKey());
            String seeds = null ;
            if(Play.isDev()) {
            	seeds = morphiaConf.getString(ConfigKey.DB_DEV_SEEDS.getKey());
            } else {
            	seeds = morphiaConf.getString(ConfigKey.DB_SEEDS.getKey());
            }

            if (StringUtils.isBlank(dbName)) {
                dbName = morphiaConf.getString(ConfigKey.DB_NAME.getKey());
                if (StringUtils.isBlank(dbName)) {
                    throw morphiaConf.reportError(ConfigKey.DB_NAME.getKey(), "Missing Morphia configuration", null);
                }
            }

            //Check if credentials parameters are present
            if (StringUtils.isBlank(username)) {
                username = morphiaConf.getString(ConfigKey.DB_USERNAME.getKey());
            }
            if (StringUtils.isBlank(password)) {
                password = morphiaConf.getString(ConfigKey.DB_PASSWORD.getKey());
            }

            if(StringUtils.isNotBlank(mongoURIstr)) {
                MongoClientURI mongoURI = new MongoClientURI(mongoURIstr);
                mongo = connect(mongoURI);
            } else if (StringUtils.isNotBlank(seeds)) {
                mongo = connect(seeds, dbName, username, password);
            } else {
                mongo = connect(
                        morphiaConf.getString(ConfigKey.DB_HOST.getKey()),
                        morphiaConf.getString(ConfigKey.DB_PORT.getKey()),
                        dbName, username, password);
            }
            
            morphia = new Morphia();
            // To prevent problem during hot-reload
            if (application.isDev()) {
                morphia.getMapper().getOptions().objectFactory = new PlayCreator();
            }
            // Configure validator
            new ValidationExtension(morphia);

            // Create datastore
            ds = morphia.createDatastore(mongo, dbName);


            MorphiaLogger.debug("Datastore [%s] created", dbName);
            // Create GridFS
            String uploadCollection = morphiaConf.getString(ConfigKey.COLLECTION_UPLOADS.getKey());
            if (StringUtils.isBlank(uploadCollection)) {
                uploadCollection = "uploads";
                MorphiaLogger.warn("Missing Morphia configuration key [%s]. Use default value instead [%s]", ConfigKey.COLLECTION_UPLOADS, "uploads");
            }
            gridfs = new GridFS(ds.getDB(), uploadCollection);
            MorphiaLogger.debug("GridFS created", "");
            MorphiaLogger.debug("Add Interceptor...", "");
            morphia.getMapper().addInterceptor(new AbstractEntityInterceptor() {

                @Override
                public void postLoad(final Object ent, final DBObject dbObj, final Mapper mapr) {
                    if (ent instanceof Model) {
                        Model m = (Model) ent;
                        m._post_Load();
                    }
                }
            });
            MorphiaLogger.debug("Classes mapping...", "");
            mapClasses();
            MorphiaLogger.debug("End of initializing Morphia", "");
        } catch (MongoException e) {
            MorphiaLogger.error(e, "Problem connecting MongoDB");
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            MorphiaLogger.error(e, "Problem mapping class");
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onStop() {
        if (isEnabled) {
            morphia = null;
            ds = null;
            gridfs = null;
            mongo.close();
        }
    }

    @Override
    public boolean enabled() {
        isEnabled = !"disabled".equals(application.configuration().getString(Constants.MORPHIA_PLUGIN_ENABLED));
        MorphiaLogger.warn(String.format("MorphiaPlugin is %s", isEnabled ? "enabled" : "disabled"));
        return isEnabled;
    }

    private void mapClasses() throws ClassNotFoundException {
        // Register all models.Class
        Set<String> classes = new HashSet<String>();
        classes.addAll(Classpath.getTypesAnnotatedWith(application, "models", Entity.class));
        classes.addAll(Classpath.getTypesAnnotatedWith(application, "models", Embedded.class));
        for (String clazz : classes) {
            MorphiaLogger.debug("mapping class: %1$s", clazz);
            morphia.map(Class.forName(clazz, true, application.classloader()));
        }
        // @see http://code.google.com/p/morphia/wiki/Datastore#Ensure_Indexes_and_Caps
        ds.ensureCaps(); //creates capped collections from @Entity
        ds.ensureIndexes(); //creates indexes from @Index annotations in your entities
    }

    private final static ConcurrentMap<String, Datastore> dataStores = new ConcurrentHashMap<String, Datastore>();

    public static Datastore ds(String dbName) {
        if (StringUtils.isBlank(dbName)) {
            return ds();
        }
        Datastore ds = dataStores.get(dbName);
        if (null == ds) {
            Datastore ds0 = morphia.createDatastore(mongo, dbName);
            ds = dataStores.putIfAbsent(dbName, ds0);
            if (null == ds) {
                ds = ds0;
            }
        }
        return ds;
    }
    
    public static Morphia morphia() {
    	return morphia ;
    }

    public static Datastore ds() {
        return ds;
    }

    public static GridFS gridFs() {
        return gridfs;
    }

    public static DB db() {
        return ds().getDB();
    }

    private MongoClient connect(MongoClientURI mongoURI) {
        try {
            return new MongoClient(mongoURI);
        }
        catch(UnknownHostException e) {
            throw Configuration.root().reportError(ConfigKey.DB_MONGOURI.getKey(), "Cannot connect to mongodb: unknown host", e);
        }
    }

    private MongoClient connect(String seeds, String dbName, String username, String password) {
        String[] sa = seeds.split("[;,\\s]+");
        List<ServerAddress> addrs = new ArrayList<ServerAddress>(sa.length);
        for (String s : sa) {
            String[] hp = s.split(":");
            if (0 == hp.length) {
                continue;
            }
            String host = hp[0];
            int port = 27017;
            if (hp.length > 1) {
                port = Integer.parseInt(hp[1]);
            }
            try {
                addrs.add(new ServerAddress(host, port));
            } catch (UnknownHostException e) {
                MorphiaLogger.error(e, "Error creating mongo connection to %s:%s", host, port);
            }
        }
        if (addrs.isEmpty()) {
            throw Configuration.root().reportError(ConfigKey.DB_SEEDS.getKey(), "Cannot connect to mongodb: no replica can be connected", null);
        }
    	MongoCredential mongoCredential = getMongoCredential(dbName, username, password) ;
        return mongoCredential == null ? new MongoClient(addrs) : new MongoClient(addrs, Arrays.asList(mongoCredential)) ;
    }

    private MongoClient connect(String host, String port, String dbName, String username, String password) {
        ServerAddress addr = null ;
        try {
        	Logger.info("HOST:" + host + " port:"+port) ;
			addr = new ServerAddress(host, Integer.parseInt(port));
		} catch (Exception e) {
            throw Configuration.root().reportError(
                    ConfigKey.DB_HOST.getKey() + "-" + ConfigKey.DB_PORT.getKey(), "Cannot connect to mongodb: error creating mongo connection",
                    null);
		}
        
    	MongoCredential mongoCredential = getMongoCredential(dbName, username, password) ;
        return mongoCredential == null ? new MongoClient(addr) : new MongoClient(addr, Arrays.asList(mongoCredential));
    }
	
    private MongoCredential getMongoCredential(String dbName, String username, String password) {
    	if (StringUtils.isBlank(username) && StringUtils.isBlank(password))
    		return null ;
    	
        if (StringUtils.isNotBlank(username) ^ StringUtils.isNotBlank(password)) {
            throw Configuration.root().reportError(ConfigKey.DB_NAME.getKey(), "Missing username or password", null);
        }

    	return MongoCredential.createMongoCRCredential(username, dbName, password.toCharArray());
    }
	
}

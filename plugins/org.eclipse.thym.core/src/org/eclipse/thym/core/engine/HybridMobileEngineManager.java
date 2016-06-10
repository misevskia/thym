/*******************************************************************************
 * Copyright (c) 2013, 2016 Red Hat, Inc. 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * 	Contributors:
 * 		 Red Hat Inc. - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.thym.core.engine;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.osgi.util.NLS;
import org.eclipse.thym.core.HybridCore;
import org.eclipse.thym.core.HybridProject;
import org.eclipse.thym.core.config.Engine;
import org.eclipse.thym.core.config.Widget;
import org.eclipse.thym.core.config.WidgetModel;
import org.eclipse.thym.core.engine.internal.cordova.CordovaEngineProvider;
import org.eclipse.thym.core.internal.cordova.CordovaCLI;
import org.eclipse.thym.core.internal.cordova.CordovaCLI.Command;
import org.eclipse.thym.core.internal.cordova.ErrorDetectingCLIResult;
import org.eclipse.thym.core.platform.PlatformConstants;
import org.osgi.framework.Version;

import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
/**
 * API for managing the engines for a {@link HybridProject}.
 * 
 * @author Gorkem Ercan
 *
 */
public class HybridMobileEngineManager {
	
	private final HybridProject project;
	
	public HybridMobileEngineManager(HybridProject project){
		this.project = project;
	}

	/**
	 * Returns the effective engines for project. 
	 * Active engines are determined as follows. 
	 * <ol>
	 * 	<li>
	 * if any platforms are listed in the platforms.json file, these are returned first, without
	 * checking config.xml.
	 * 	</li>
	 * 	<li>
	 * if <i>engine</i> entries exist on config.xml match them to installed cordova engines. 
	 * 	</li>
	 * 	<li>
	 * if no <i>engine</i> entries exists on config.xml returns the default engines.
	 * if default engines can be determined.
	 * 	</li>
	 * @see HybridMobileEngineManager#defaultEngines()
	 * @return possibly empty array of {@link HybridMobileEngine}s
	 */
	public HybridMobileEngine[] getActiveEngines(){
		HybridMobileEngine[] platformJsonEngines = getActiveEnginesFromPlatformsJson();
		if (platformJsonEngines.length > 0) {
			return platformJsonEngines;
		}
		
		try{
			WidgetModel model = WidgetModel.getModel(project);
			Widget w = model.getWidgetForRead();
			List<Engine> engines = null; 
			if(w != null ){
				engines = w.getEngines();
			}
			if(engines == null || engines.isEmpty() ){
				HybridCore.log(IStatus.INFO, "No engine information exists on config.xml. Falling back to default engines",null );
				return defaultEngines();
			}
			CordovaEngineProvider engineProvider = new CordovaEngineProvider();
			ArrayList<HybridMobileEngine> activeEngines = new ArrayList<HybridMobileEngine>();
			final List<HybridMobileEngine> availableEngines = engineProvider.getAvailableEngines();
			for (Engine engine : engines) {
				for (HybridMobileEngine hybridMobileEngine : availableEngines) {
					if(engineMatches(engine, hybridMobileEngine)){
						activeEngines.add(hybridMobileEngine);
						break;
					}
				}
			}
			return activeEngines.toArray(new HybridMobileEngine[activeEngines.size()]);
		} catch (CoreException e) {
			HybridCore.log(IStatus.WARNING, "Engine information can not be read", e);
		}
		HybridCore.log(IStatus.WARNING, "Could not determine the engines used", null);
		return new HybridMobileEngine[0];
	}

	private boolean engineMatches(Engine configEngine, HybridMobileEngine engine){
		//null checks needed: sometimes we encounter engines without a name or version attribute.
		if(engine.isManaged()){

			// Since cordova uses semver, version numbers in config.xml can begin with '~' or '^'.
			// This breaks the check below, since engine.getVersion() is stored without.
			if (configEngine.getSpec() != null) {
				String spec = configEngine.getSpec();
				if (spec.startsWith("~") || spec.startsWith("^")) {
					spec = spec.substring(1);
				}
				return configEngine.getName() != null && configEngine.getName().equals(engine.getId())
						&& spec.equals(engine.getVersion());
			} else {
				return false;
			}
		}else{
			return engine.getLocation().isValidPath(configEngine.getSpec()) 
					&& engine.getLocation().equals(new Path(configEngine.getSpec()));
		}
	}

	/**
	 * Returns the active engines for the project by looking at
	 * the values stored in platforms.json.
	 * 
	 * </p>
	 * If no engines are found in platforms.json, returns an empty array.
	 * The file platforms.json is where the currently active cordova engines
	 * are stored, (semi-)independently of what is stored in config.xml.
	 *
	 * @see HybridMobileEngineManager#defaultEngines()
	 * @see HybridMobileEngineManager#getActiveEngines()
	 * @return possibly empty array of {@link HybridMobileEngine}s
	 */
	public HybridMobileEngine[] getActiveEnginesFromPlatformsJson(){
		try {
			IFile file = project.getPlatformsJSONFile();
			if (file == null) {
				return new HybridMobileEngine[0];
			}
			
			CordovaEngineProvider engineProvider = new CordovaEngineProvider();
			List<HybridMobileEngine> activeEngines = new ArrayList<HybridMobileEngine>();
			final List<HybridMobileEngine> availableEngines = engineProvider.getAvailableEngines();
			
			JsonParser parser = new JsonParser();
			JsonObject root = parser.parse(new InputStreamReader(file.getContents())).getAsJsonObject();
			for (String platform : PlatformConstants.SUPPORTED_PLATFORMS) {
				if (root.has(platform)) {
					HybridMobileEngine engine = 
							getHybridMobileEngine(platform, root.get(platform).getAsString());
					if (engine != null) {
						activeEngines.add(engine);
					}
				}
			}
			return activeEngines.toArray(new HybridMobileEngine[activeEngines.size()]);

		} catch (JsonIOException e) {
			HybridCore.log(IStatus.WARNING, "Error reading input stream from platforms.json", e);
		} catch (JsonSyntaxException e) {
			HybridCore.log(IStatus.WARNING, "platforms.json has errors", e);
		} catch (CoreException e) {
			HybridCore.log(IStatus.WARNING, "Error while opening platforms.json", e);
		}
		return new HybridMobileEngine[0];
	}

	/**
	 * Returns the HybridMobileEngine that corresponds to the provide name and spec. 
	 * Searches through available engines for a match, and may return null if no 
	 * matching engine is found.
	 * 
	 * @return The HybridMobileEngine corresponding to name and spec, or null if 
	 * a match cannot be found.
	 */
	private HybridMobileEngine getHybridMobileEngine(String name, String spec) {
		CordovaEngineProvider engineProvider = new CordovaEngineProvider();
		final List<HybridMobileEngine> availableEngines = engineProvider.getAvailableEngines();
		for (HybridMobileEngine engine : availableEngines) {
			if (engine.isManaged() && engine.getId().equals(name)
								   && engine.getVersion().equals(spec)) {
				return engine;
			}
		}
		return null;
	}

	/**
	 * Returns the {@link HybridMobileEngine}s specified within Thym preferences.
	 *
	 * </p> 
	 * If no engines have been added, returns an empty array. Otherwise returns
	 * either the user's preference, or, by default, the most recent version
	 * available for each platform.
	 *
	 * @see HybridMobileEngineManager#getActiveEngines()
	 * @return possibly empty array of {@link HybridMobileEngine}s
	 */
	public static HybridMobileEngine[] defaultEngines() {
		CordovaEngineProvider engineProvider = new CordovaEngineProvider();
		List<HybridMobileEngine> availableEngines = engineProvider.getAvailableEngines();
		if(availableEngines == null || availableEngines.isEmpty() ){
			return new HybridMobileEngine[0];
		}
		ArrayList<HybridMobileEngine> defaults = new ArrayList<HybridMobileEngine>();
		
		String pref =  Platform.getPreferencesService().getString(PlatformConstants.HYBRID_UI_PLUGIN_ID, PlatformConstants.PREF_DEFAULT_ENGINE, null, null);
		if(pref != null && !pref.isEmpty()){
			String[] engineStrings = pref.split(",");
			for (String engineString : engineStrings) {
				String[] engineInfo = engineString.split(":");
				for (HybridMobileEngine hybridMobileEngine : availableEngines) {
					if(engineInfo[0].equals(hybridMobileEngine.getId()) && engineInfo[1].equals(hybridMobileEngine.getVersion())){
						defaults.add(hybridMobileEngine);
					}
				}
			}
		}else{
			HashMap<String, HybridMobileEngine> platforms = new HashMap<String, HybridMobileEngine>();
			for (HybridMobileEngine hybridMobileEngine : availableEngines) {
				if(platforms.containsKey(hybridMobileEngine.getId())){
					HybridMobileEngine existing = platforms.get(hybridMobileEngine.getId());
					try{
						Version ev = Version.parseVersion(existing.getVersion());
						Version hv = Version.parseVersion(hybridMobileEngine.getVersion());
						if(hv.compareTo(ev) >0 ){
							platforms.put(hybridMobileEngine.getId(), hybridMobileEngine);
						}
					}catch(IllegalArgumentException e){
						//catch the version parse errors because version field may actually contain 
						//git urls and local paths.
					}
				}else{
					platforms.put(hybridMobileEngine.getId(),hybridMobileEngine);
				}
			}
			defaults.addAll(platforms.values());
		}
		return defaults.toArray(new HybridMobileEngine[defaults.size()]);
	}

	/**
	 * Persists engine information to config.xml. 
	 * Removes existing engines form the project.
	 * Calls cordova prepare so that the new engines are restored.
	 * 
	 * @param engine
	 * @throws CoreException
	 */
	public void updateEngines(final HybridMobileEngine[] engines) throws CoreException{
		WorkspaceJob updateJob = new WorkspaceJob(NLS.bind("Update Cordova Engines for {0}",project.getProject().getName()) ) {
			
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {

				WidgetModel model = WidgetModel.getModel(project);
				Widget w = model.getWidgetForEdit();
				List<Engine> existingEngines = w.getEngines();
				CordovaCLI cordova = CordovaCLI.newCLIforProject(project);
				SubMonitor sm = SubMonitor.convert(monitor,100);
				if(existingEngines != null ){
					for (Engine existingEngine : existingEngines) {
						if(isEngineRemoved(existingEngine, engines)){
							cordova.platform(Command.REMOVE, sm,existingEngine.getName());
						}
						w.removeEngine(existingEngine);
					}
				}
				sm.worked(30);
				for (HybridMobileEngine engine : engines) {
					Engine e = model.createEngine(w);
					e.setName(engine.getId());
					if(!engine.isManaged()){
						e.setSpec(engine.getLocation().toString());
					}else{
						e.setSpec(engine.getVersion());
					}
					w.addEngine(e);
				}
				model.save();
				IStatus status = Status.OK_STATUS;
				status = cordova.prepare(sm.newChild(40), "").convertTo(ErrorDetectingCLIResult.class).asStatus();
				project.getProject().refreshLocal(IResource.DEPTH_INFINITE, sm.newChild(30));
				sm.done();
				return status;
			}
		};
		ISchedulingRule rule = ResourcesPlugin.getWorkspace().getRuleFactory().modifyRule(this.project.getProject());
		updateJob.setRule(rule);
		updateJob.schedule();
	}
	
	private boolean isEngineRemoved(final Engine engine, final HybridMobileEngine[] engines){
		for (HybridMobileEngine hybridMobileEngine : engines) {
			if(hybridMobileEngine.getId().equals(engine.getName()) && hybridMobileEngine.getVersion().equals(engine.getSpec())){
				return false;
			}
		}
		return true;
	}

}

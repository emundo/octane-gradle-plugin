package eu.emundo.generator.generate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import com.hpe.adm.nga.sdk.Octane;
import com.hpe.adm.nga.sdk.Octane.OctaneCustomSettings;
import com.hpe.adm.nga.sdk.authentication.SimpleClientAuthentication;
import com.hpe.adm.nga.sdk.entities.OctaneCollection;
import com.hpe.adm.nga.sdk.metadata.EntityMetadata;
import com.hpe.adm.nga.sdk.metadata.FieldMetadata;
import com.hpe.adm.nga.sdk.metadata.Metadata;
import com.hpe.adm.nga.sdk.metadata.features.Feature;
import com.hpe.adm.nga.sdk.metadata.features.RestFeature;
import com.hpe.adm.nga.sdk.metadata.features.SubTypesOfFeature;
import com.hpe.adm.nga.sdk.model.EntityModel;
import com.hpe.adm.nga.sdk.model.LongFieldModel;
import com.hpe.adm.nga.sdk.model.ReferenceFieldModel;
import com.hpe.adm.nga.sdk.model.StringFieldModel;
import com.hpe.adm.nga.sdk.query.Query;
import com.hpe.adm.nga.sdk.query.QueryMethod;

/**
 * <p>
 * The class that generates entities based on the metadata from the given ALM
 * Octane server This class generates models based on the
 * {@link com.hpe.adm.nga.sdk.model.TypedEntityModel}, entity lists based on
 * {@link com.hpe.adm.nga.sdk.entities.TypedEntityList} and Lists &amp; Phases
 * objects which represents those entities on the server and turns them into
 * typed enums.
 * </p>
 * <p>
 * The user that calls the generation must have the workspace member of the
 * given workspace.
 * </p>
 * <p>
 * UDFs are generated if they are part of the metadata for that workspace. That
 * means that the generated entities should be able to be reused over different
 * workspaces within the same shared space. However some business rules could
 * cause different behaviour in different Workspaces. See the ALM Octane
 * documentation for more information
 * </p>
 */
public class GenerateModels {

	private final Template template, interfaceTemplate, entityListTemplate, phasesTemplate, listTemplate;
	private final File modelDirectory, entitiesDirectory, enumsDirectory, listsDirectory;
	private final List<String> ignoredListIds;

	/**
	 * Initialise the class with the output directory. This should normally be
	 * in a project that would be imported into the main Java project
	 *
	 * @param outputDirectory
	 *            Where all the generated files will be placed
	 * @param ignoredListIds
	 *            Comma separated list with list_node ids, which schould be
	 *            ignored during lists generation
	 */
	public GenerateModels(final File outputDirectory, final String ignoredListIds) {
		if (StringUtils.isNotBlank(ignoredListIds)) {
			this.ignoredListIds = Arrays.asList(StringUtils.split(ignoredListIds, ","));
		} else {
			this.ignoredListIds = null;
		}
		final File packageDirectory = new File(outputDirectory, "/com/hpe/adm/nga/sdk");
		modelDirectory = new File(packageDirectory, "model");
		modelDirectory.mkdirs();
		entitiesDirectory = new File(packageDirectory, "entities");
		entitiesDirectory.mkdirs();
		enumsDirectory = new File(packageDirectory, "enums");
		listsDirectory = new File(enumsDirectory, "lists");
		listsDirectory.mkdirs();

		final VelocityEngine velocityEngine = new VelocityEngine();
		velocityEngine.setProperty("resource.loader", "class");
		velocityEngine.setProperty("class.resource.loader.description", "Velocity Classpath Resource Loader");
		velocityEngine.setProperty(VelocityEngine.RUNTIME_LOG_LOGSYSTEM, new SLF4JLogChute());
		velocityEngine.setProperty("class.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");

		velocityEngine.init();

		template = velocityEngine.getTemplate("/EntityModel.vm");
		interfaceTemplate = velocityEngine.getTemplate("/Entity.vm");
		entityListTemplate = velocityEngine.getTemplate("/TypedEntityList.vm");
		phasesTemplate = velocityEngine.getTemplate("/Phases.vm");
		listTemplate = velocityEngine.getTemplate("/List.vm");
	}

	/**
	 * Run the actual generation
	 *
	 * @param clientId
	 *            The client id
	 * @param clientSecret
	 *            The client secret
	 * @param server
	 *            The server including the protocol and port
	 * @param sharedSpace
	 *            The SS id
	 * @param workSpace
	 *            The WS id
	 * @param doNotValidateCertificate
	 *            Disables validating server SSL certificates
	 * @param techPreview
	 *            API Mode
	 * @throws IOException
	 *             A problem with the generation of the entities
	 * @throws GeneralSecurityException
	 *             A problem with the generation of the entities
	 */
	public void generate(final String clientId, final String clientSecret, final String server, final long sharedSpace, final long workSpace,
			final boolean doNotValidateCertificate, final boolean techPreview) throws IOException, GeneralSecurityException {
		// work around for work_items_root
		final OctaneCustomSettings octaneCustomSettings = new OctaneCustomSettings();
		octaneCustomSettings.set(OctaneCustomSettings.Setting.TRUST_ALL_CERTS, doNotValidateCertificate);
		final Octane octanePrivate = new Octane.Builder(new SimpleClientAuthentication(clientId, clientSecret, GeneratorHelper.TECHNICAL_PREVIEW_APIMODE), null)
				.sharedSpace(sharedSpace)
				.workSpace(workSpace)
				.Server(server)
				.settings(octaneCustomSettings)
				.build();
		final EntityMetadata work_items_root = octanePrivate.metadata().entities("work_item_root").execute().iterator().next();
		final Collection<FieldMetadata> work_items_rootFields = octanePrivate.metadata().fields("work_item_root").execute();

		octanePrivate.signOut();

		final Octane octane = new Octane.Builder(
				new SimpleClientAuthentication(clientId, clientSecret, techPreview ? GeneratorHelper.TECHNICAL_PREVIEW_APIMODE : null), null)
						.sharedSpace(sharedSpace)
						.workSpace(workSpace)
						.Server(server)
						.settings(octaneCustomSettings)
						.build();
		final Metadata metadata = octane.metadata();
		final Collection<EntityMetadata> entityMetadata = metadata.entities().execute();
		entityMetadata.add(work_items_root);

		final Map<String, String> logicalNameToListsMap = generateLists(octane);
		final Set<String> availablePhases = generatePhases(octane);

		for (final EntityMetadata entityMetadatum : entityMetadata) {
			final String name = entityMetadatum.getName();
			if (entityShouldNotBeGenerated(name))
				continue;
			final String interfaceName = GeneratorHelper.camelCaseFieldName(name) + "Entity";
			final Collection<FieldMetadata> fieldMetadata = generateEntity(work_items_rootFields, metadata, entityMetadata, entityMetadatum, name,
					interfaceName, logicalNameToListsMap, availablePhases);
			generateInterface(entityMetadatum, name, interfaceName);
			generateEntityList(entityMetadatum, name, fieldMetadata);
		}
		octane.signOut();
	}

	private boolean listShouldNotBeGenerated(final String id) {
		return this.ignoredListIds != null && this.ignoredListIds.contains(id);
	}

	/**
	 * There are a few fields that cannot be generated due to inconsistencies.
	 * These could have special cases but it is simpler to exclude them from
	 * generation. If there is a problem then they can be checked on an
	 * individual basis
	 *
	 * @param name
	 *            The entity that should be checked
	 * @return Whether this entity should be ignored and therefore not generated
	 */
	private boolean entityShouldNotBeGenerated(String name) {
		/*
		 * @Since 15.0.20 The run_history's id is integer even though it should
		 * be string. It would be extremely complicated to make a special case
		 * for run_history id as long Therefore until this is fixed in Octane -
		 * the entity will be ignored
		 */
		if (name.equals("run_history")) {
			return true;
		}
		if (name.equals("history_log")) {
			return true;
		}
		if (name.equals("audit")) {
			return true;
		}

		/*
		 * @Since 15.1.20 field_metadata is a special case in that it is used
		 * when defining UDFs. It causes problems in the entity generation due
		 * to the list node not having a reference. It is unlikely that this
		 * would be used by the SDK so is ignored for now. If this does cause an
		 * issue we could look into fixing this in the future
		 */
		if (name.startsWith("field_metadata")) {
			return true;
		}

		/*
		 * @Since 15.1.20 log entities have the ID marked as an integer and not
		 * as a string. This causes issues with creating the entity. A defect
		 * has been raised in Octane to fix this
		 */
		if (name.startsWith("log")) {
			return true;
		}

		/*
		 * @Since 15.1.20 This entity "overrides" the type field for its own use
		 * which causes issues. A defect has been raised in Octane to fix this
		 */
		return name.equals("ci_parameter");
	}

	private Map<String, String> generateLists(final Octane octane) throws IOException {
		// since octane v12.60.35.103 does not return root list_nodes within
		// list_nodes call
		final Collection<EntityModel> rootNodes = octane.entityList("list_nodes")
				.get()
				.addFields("name", "id", "logical_name", "activity_level")
				.query(Query.statement("list_root", QueryMethod.EqualTo, null).build())
				.execute();

		final List<EntityModel> listNodes = new ArrayList<>();
		final List<EntityModel> rootNodesToRemove = new ArrayList<>();
		for (EntityModel rootNode : rootNodes) {
			if (listShouldNotBeGenerated(rootNode.getId())) {
				rootNodesToRemove.add(rootNode);
				continue;
			}
			final OctaneCollection<EntityModel> models = octane.entityList("list_nodes")
					.get()
					.addFields("name", "list_root", "id", "logical_name", "activity_level")
					.query(Query.statement("list_root", QueryMethod.EqualTo, Query.statement("id", QueryMethod.EqualTo, rootNode.getId()))
							.and(Query.statement("activity_level", QueryMethod.LessThan, 2))
							.build())
					.execute();
			listNodes.addAll(models);
		}
		rootNodes.removeAll(rootNodesToRemove);

		final Map<String, List<String[]>> mappedListNodes = new HashMap<>();
		final Map<String, String> logicalNameToNameMap = new HashMap<>();

		listNodes.stream().sorted(Comparator.comparing(this::getEntityModelName)).forEach(listNode -> {
			final String rootId;
			final ReferenceFieldModel list_root = (ReferenceFieldModel) listNode.getValue("list_root");
			final EntityModel list_rootValue = list_root.getValue();
			rootId = list_rootValue.getId();

			if (((LongFieldModel) listNode.getValue("activity_level")).getValue().equals(1L)) {
				System.out.println("List entry is deprecated: " + ((StringFieldModel) listNode.getValue("name")).getValue());
			}

			mappedListNodes.computeIfAbsent(rootId, k -> new ArrayList<>())
					.add(new String[] { //
							getEntityModelName(listNode), //
							((StringFieldModel) listNode.getValue("id")).getValue(), //
							((StringFieldModel) listNode.getValue("name")).getValue().replace("\\", "\\\\"), //
							((LongFieldModel) listNode.getValue("activity_level")).getValue().toString() });

		});

		// deduplicate list entries
		mappedListNodes.forEach((key, value) -> {
			final Map<String, List<String[]>> deDupMap = new TreeMap<>();
			value.forEach(strings -> {
				deDupMap.computeIfAbsent(strings[0], k -> new ArrayList<>()).add(strings);
			});
			value = deDupMap.values().stream().peek(list -> {
				if (list.size() > 1) {
					final AtomicInteger counter = new AtomicInteger();
					list.forEach(strings -> {
						strings[0] += "__" + (counter.getAndIncrement() + 1);
					});
				}
			}).flatMap(Collection::stream).collect(Collectors.toList());
		});

		rootNodes.forEach(rootNode -> {
			final String name = getEntityModelName(rootNode);
			logicalNameToNameMap.put(((StringFieldModel) rootNode.getValue("logical_name")).getValue(), name);
			final List<String[]> strings = mappedListNodes.computeIfAbsent(rootNode.getId(), k -> new ArrayList<>());
			strings.add(0, new String[] { //
					name, //
					rootNode.getId(), //
					((StringFieldModel) rootNode.getValue("name")).getValue(), //
					((LongFieldModel) rootNode.getValue("activity_level")).getValue().toString() });
		});

		final Map<String, List<String[]>> sortedMappedListNodes = new TreeMap<>();
		mappedListNodes.values().forEach(strings -> sortedMappedListNodes.put(strings.get(0)[0], strings));

		for (final Map.Entry<String, List<String[]>> sortedMappedListEntry : sortedMappedListNodes.entrySet()) {
			final String listId = sortedMappedListEntry.getValue().get(0)[1];
			System.out.println("Create list class: " + sortedMappedListEntry.getKey());
			final List<String> deprecatedEnums = sortedMappedListEntry.getValue()
					.stream()
					.skip(1) // skip root first
					.filter(enums -> "1".equals(enums[3])) // filter all
					// deprecated list
					// entries
					.map(list -> list[0])
					.collect(Collectors.toList());
			final VelocityContext velocityContext = new VelocityContext();
			velocityContext.put("listItems", sortedMappedListEntry.getValue());
			velocityContext.put("deprecatedItems", deprecatedEnums);
			final FileWriter fileWriter = new FileWriter(new File(listsDirectory, sortedMappedListEntry.getKey() + ".java"));
			listTemplate.merge(velocityContext, fileWriter);
			fileWriter.close();
		}
		return logicalNameToNameMap;
	}

	private String getEntityModelName(final EntityModel listNode) {
		return GeneratorHelper.handleSingeUnderscoreEnum(GeneratorHelper.removeAccents(((StringFieldModel) listNode.getValue("name")).getValue())
				.replaceAll(" ", "_")
				.replaceAll("^\\d", "_$0")
				.replaceAll("\\W", "_")
				.toUpperCase());
	}

	private Set<String> generatePhases(final Octane octane) throws IOException {
		final Map<String, List<String[]>> phaseMap = new TreeMap<>();
		final Collection<EntityModel> phases = octane.entityList("phases")
				.get()
				.addFields("id", "name", "entity")
				.query(Query.statement("activity_level", QueryMethod.EqualTo, 0).build())
				.execute();

		phases.stream().sorted(Comparator.comparing(phase -> ((StringFieldModel) phase.getValue("name")).getValue())).forEach(phase -> {
			final List<String[]> phaseValueList = new ArrayList<>();
			phaseValueList.add(new String[] { //
					phase.getId(), //
					getEntityModelName(phase).toUpperCase(), //
					((StringFieldModel) phase.getValue("name")).getValue(), //
					((StringFieldModel) phase.getValue("entity")).getValue() //
			});
			phaseMap.merge(GeneratorHelper.camelCaseFieldName(((StringFieldModel) phase.getValue("entity")).getValue(), true), phaseValueList, //
					(existingValues, newValues) -> {
						existingValues.addAll(newValues);
						return existingValues;
					});
		});

		final VelocityContext velocityContext = new VelocityContext();
		velocityContext.put("phaseMap", phaseMap);
		final FileWriter fileWriter = new FileWriter(new File(enumsDirectory, "Phases.java"));
		phasesTemplate.merge(velocityContext, fileWriter);
		fileWriter.close();

		return phaseMap.keySet();
	}

	private Collection<FieldMetadata> generateEntity(final Collection<FieldMetadata> work_items_rootFields, final Metadata metadata,
			final Collection<EntityMetadata> entityMetadata, final EntityMetadata entityMetadatum, final String name, final String interfaceName,
			final Map<String, String> logicalNameToListsMap, final Set<String> availablePhases) throws IOException {
		final List<FieldMetadata> fieldMetadata = new ArrayList<>(name.equals("work_item_root") ? work_items_rootFields : metadata.fields(name).execute());
		fieldMetadata.sort(Comparator.comparing(FieldMetadata::getName));
		final TreeMap<String, List<String>> collectedReferences = fieldMetadata.stream()
				.filter(FieldMetadata::isRequired)
				.collect(Collectors.toMap(FieldMetadata::getName, fieldMetadata1 -> {
					final List<String> references = new ArrayList<>();
					final String className = GeneratorHelper.camelCaseFieldName(entityMetadatum.getName());
					if (fieldMetadata1.getName().equals("phase") && availablePhases.contains(className)) {
						references.add("com.hpe.adm.nga.sdk.enums.Phases." + className + "Phase");
					} else if (fieldMetadata1.getFieldType() == FieldMetadata.FieldType.Reference) {
						if ((!entityMetadatum.getName().equals("list_node"))
								&& (fieldMetadata1.getFieldTypedata().getTargets()[0].getType().equals("list_node"))) {
							final String listName = logicalNameToListsMap.get(fieldMetadata1.getFieldTypedata().getTargets()[0].logicalName());
							references.add("com.hpe.adm.nga.sdk.enums.lists." + listName);
						} else {
							final GeneratorHelper.ReferenceMetadata referenceMetadata = GeneratorHelper.getAllowedSuperTypesForReference(fieldMetadata1,
									entityMetadata);
							if (fieldMetadata1.getFieldTypedata().isMultiple()) {
								references.add(referenceMetadata.getReferenceClassForSignature());
							} else {
								if (referenceMetadata.hasTypedReturn()) {
									references.addAll(referenceMetadata.getReferenceTypes()
											.stream()
											.map(type -> GeneratorHelper.camelCaseFieldName(type).concat("EntityModel"))
											.collect(Collectors.toSet()));
								}
								if (referenceMetadata.hasNonTypedReturn()) {
									references.add("EntityModel");
								}
							}
						}
					} else {
						references.add(GeneratorHelper.getFieldTypeAsJava(fieldMetadata1.getFieldType()));
					}

					return references;
				}, (strings, strings2) -> {
					throw new IllegalStateException("problem merging map");
				}, TreeMap::new));

		final Set<List<String[]>> requiredFields = new HashSet<>();
		if (!collectedReferences.isEmpty()) {
			expandCollectedReferences(collectedReferences, new int[collectedReferences.size()], 0, requiredFields);
		}
		// Die Id muss immer vom Typ String sein, da es sonst Compile fehler
		// gibt. siehe com.hpe.adm.nga.sdk.model.Entity
		// fieldMetadata.forEach(field -> {
		// if (field.getName().equalsIgnoreCase("id")) {
		// field.setFieldType(FieldMetadata.FieldType.String);
		// }
		// });

		final VelocityContext velocityContext = new VelocityContext();
		velocityContext.put("interfaceName", interfaceName);
		velocityContext.put("entityMetadata", entityMetadatum);
		velocityContext.put("fieldMetadata", fieldMetadata);
		velocityContext.put("logicalNameToListsMap", logicalNameToListsMap);
		velocityContext.put("entityMetadataCollection", entityMetadata);
		velocityContext.put("GeneratorHelper", GeneratorHelper.class);
		velocityContext.put("SortHelper", SortHelper.class);
		velocityContext.put("entityMetadataWrapper", GeneratorHelper.entityMetadataWrapper(entityMetadatum));
		velocityContext.put("availablePhases", availablePhases);
		velocityContext.put("requiredFields", requiredFields);

		final FileWriter fileWriter = new FileWriter(new File(modelDirectory, GeneratorHelper.camelCaseFieldName(name) + "EntityModel.java"));
		template.merge(velocityContext, fileWriter);

		fileWriter.close();
		return fieldMetadata;
	}

	private void expandCollectedReferences(final TreeMap<String, List<String>> collectedReferences, final int[] positions, final int pointer,
			final Set<List<String[]>> output) {
		final Object[] keyArray = collectedReferences.keySet().toArray();
		final Object o = keyArray[pointer];
		for (int i = 0; i < collectedReferences.get(o).size(); ++i) {
			if (pointer == positions.length - 1) {
				final List<String[]> outputLine = new ArrayList<>(positions.length);
				for (int j = 0; j < positions.length; ++j) {
					outputLine.add(new String[] { (String) keyArray[j], collectedReferences.get(keyArray[j]).get(positions[j]) });
				}
				output.add(outputLine);
			} else {
				expandCollectedReferences(collectedReferences, positions, pointer + 1, output);
			}
			positions[pointer]++;
		}
		positions[pointer] = 0;
	}

	private void generateInterface(final EntityMetadata entityMetadatum, final String name, final String interfaceName) throws IOException {
		// interface
		final VelocityContext interfaceVelocityContext = new VelocityContext();
		final Optional<Feature> subTypeOfFeature = entityMetadatum.features().stream().filter(feature -> feature instanceof SubTypesOfFeature).findAny();

		interfaceVelocityContext.put("interfaceName", interfaceName);
		interfaceVelocityContext.put("name", name);
		interfaceVelocityContext.put("superInterfaceName",
				(subTypeOfFeature.map(feature -> GeneratorHelper.camelCaseFieldName(((SubTypesOfFeature) feature).getType())).orElse("")) + "Entity");

		final FileWriter interfaceFileWriter = new FileWriter(new File(modelDirectory, GeneratorHelper.camelCaseFieldName(name) + "Entity.java"));
		interfaceTemplate.merge(interfaceVelocityContext, interfaceFileWriter);

		interfaceFileWriter.close();
	}

	private void generateEntityList(final EntityMetadata entityMetadatum, final String name, final Collection<FieldMetadata> fieldMetadata) throws IOException {
		// entityList
		final Optional<Feature> hasRestFeature = entityMetadatum.features().stream().filter(feature -> feature instanceof RestFeature).findFirst();
		// if not then something is wrong!
		if (hasRestFeature.isPresent()) {
			final RestFeature restFeature = (RestFeature) hasRestFeature.get();

			final VelocityContext entityListVelocityContext = new VelocityContext();
			entityListVelocityContext.put("helper", GeneratorHelper.class);
			entityListVelocityContext.put("type", GeneratorHelper.camelCaseFieldName(name));
			entityListVelocityContext.put("url", restFeature.getUrl());
			entityListVelocityContext.put("availableFields",
					fieldMetadata.stream().sorted(Comparator.comparing(FieldMetadata::getName)).collect(Collectors.toList()));
			entityListVelocityContext.put("sortableFields",
					fieldMetadata.stream().filter(FieldMetadata::isSortable).sorted(Comparator.comparing(FieldMetadata::getName)).collect(Collectors.toList()));

			final String[] restFeatureMethods = restFeature.getMethods();
			for (final String restFeatureMethod : restFeatureMethods) {
				switch (restFeatureMethod) {
				case "GET":
					entityListVelocityContext.put("hasGet", true);
					break;
				case "POST":
					entityListVelocityContext.put("hasCreate", true);
					break;
				case "PUT":
					entityListVelocityContext.put("hasUpdate", true);
					break;
				case "DELETE":
					entityListVelocityContext.put("hasDelete", true);
					break;
				}
			}

			final FileWriter entityListFileWriter = new FileWriter(new File(entitiesDirectory, GeneratorHelper.camelCaseFieldName(name) + "EntityList.java"));
			entityListTemplate.merge(entityListVelocityContext, entityListFileWriter);

			entityListFileWriter.close();
		}
	}
}

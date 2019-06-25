package io.dinject.javalin.generator.openapi;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Help build OpenAPI Schema objects.
 */
class SchemaDocBuilder {

  private static final String APP_FORM = "application/x-www-form-urlencoded";
  private static final String APP_JSON = "application/json";

  private final Types types;
  private final KnownTypes knownTypes;
  private final TypeMirror iterableType;
  private final TypeMirror mapType;

  private final Map<String, Schema> schemas = new TreeMap<>();

  SchemaDocBuilder(Types types, Elements elements) {
    this.types = types;
    this.knownTypes = new KnownTypes();
    this.iterableType = types.erasure(elements.getTypeElement("java.lang.Iterable").asType());
    this.mapType = types.erasure(elements.getTypeElement("java.util.Map").asType());
  }

  Map<String, Schema> getSchemas() {
    return schemas;
  }

  Content createContent(TypeMirror returnType, String mediaType) {
    MediaType mt = new MediaType();
    mt.setSchema(toSchema(returnType));
    Content content = new Content();
    content.addMediaType(mediaType, mt);
    return content;
  }

  /**
   * Add parameter as a form parameter.
   */
  void addFormParam(Operation operation, String varName, Schema schema) {
    RequestBody body = requestBody(operation);
    Schema formSchema = requestFormParamSchema(body);
    formSchema.addProperties(varName, schema);
  }

  private Schema requestFormParamSchema(RequestBody body) {

    final Content content = body.getContent();
    MediaType mediaType = content.get(APP_FORM);

    Schema schema;
    if (mediaType != null) {
      schema = mediaType.getSchema();
    } else {
      schema = new Schema();
      schema.setType("object");
      mediaType = new MediaType();
      mediaType.schema(schema);
      content.addMediaType(APP_FORM, mediaType);
    }
    return schema;
  }

  /**
   * Add as request body.
   */
  void addRequestBody(Operation operation, Schema schema, boolean asForm, String description) {

    RequestBody body = requestBody(operation);
    body.setDescription(description);

    MediaType mt = new MediaType();
    mt.schema(schema);

    String mime = asForm ? APP_FORM : APP_JSON;
    body.getContent().addMediaType(mime, mt);
  }

  private RequestBody requestBody(Operation operation) {

    RequestBody body = operation.getRequestBody();
    if (body == null) {
      body = new RequestBody();
      body.setRequired(true);
      Content content = new Content();
      body.setContent(content);
      operation.setRequestBody(body);
    }
    return body;
  }

  Schema<?> toSchema(TypeMirror type) {

    Schema<?> schema = knownTypes.createSchema(type.toString());
    if (schema != null) {
      return schema;
    }
    if (types.isAssignable(type, mapType)) {
      return buildMapSchema(type);
    }

    if (type.getKind() == TypeKind.ARRAY) {
      return buildArraySchema(type);
    }

    if (types.isAssignable(type, iterableType)) {
      return buildIterableSchema(type);
    }

    return buildObjectSchema(type);
  }

  private Schema<?> buildObjectSchema(TypeMirror type) {

    String objectSchemaKey = getObjectSchemaName(type);

    Schema objectSchema = schemas.get(objectSchemaKey);
    if (objectSchema == null) {
      objectSchema = createObjectSchema(type);
      schemas.put(objectSchemaKey, objectSchema);
    }

    ObjectSchema obRef = new ObjectSchema();
    obRef.$ref("#/components/schemas/" + objectSchemaKey);
    return obRef;
  }

  private Schema<?> buildIterableSchema(TypeMirror type) {

    Schema<?> itemSchema = new ObjectSchema().format("unknownIterableType");

    if (type.getKind() == TypeKind.DECLARED) {
      List<? extends TypeMirror> typeArguments = ((DeclaredType) type).getTypeArguments();
      if (typeArguments.size() == 1) {
        itemSchema = toSchema(typeArguments.get(0));
      }
    }

    ArraySchema arraySchema = new ArraySchema();
    arraySchema.setItems(itemSchema);
    return arraySchema;
  }

  private Schema<?> buildArraySchema(TypeMirror type) {

    ArrayType arrayType = types.getArrayType(type);
    Schema<?> itemSchema = toSchema(arrayType.getComponentType());

    ArraySchema arraySchema = new ArraySchema();
    arraySchema.setItems(itemSchema);
    return arraySchema;
  }

  private Schema<?> buildMapSchema(TypeMirror type) {

    Schema<?> valueSchema = new ObjectSchema().format("unknownMapValueType");

    if (type.getKind() == TypeKind.DECLARED) {
      DeclaredType declaredType = (DeclaredType) type;
      List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
      if (typeArguments.size() == 2) {
        valueSchema = toSchema(typeArguments.get(1));
      }
    }

    MapSchema mapSchema = new MapSchema();
//      mapSchema.type();
//      mapSchema.set$ref();
//      mapSchema.setFormat();
    mapSchema.setAdditionalProperties(valueSchema);
    return mapSchema;
  }

  private String getObjectSchemaName(TypeMirror type) {
    String canonicalName = type.toString();
    int pos = canonicalName.lastIndexOf('.');
    if (pos > -1) {
      canonicalName = canonicalName.substring(pos + 1);
    }
    return canonicalName;
  }

  private ObjectSchema createObjectSchema(TypeMirror objectType) {

    ObjectSchema objectSchema = new ObjectSchema();

    Element element = types.asElement(objectType);
    for (VariableElement field : allFields(element)) {
      Schema<?> propSchema = toSchema(field.asType());
      // max,min,required,deprecated ?
      objectSchema.addProperties(field.getSimpleName().toString(), propSchema);
    }
    return objectSchema;
  }


  /**
   * Gather all the fields (properties) for the given bean element.
   */
  private List<VariableElement> allFields(Element element) {

    List<VariableElement> list = new ArrayList<>();
    gatherProperties(list, element);
    return list;
  }

  /**
   * Recursively gather all the fields (properties) for the given bean element.
   */
  private void gatherProperties(List<VariableElement> fields, Element element) {

    if (element == null) {
      return;
    }
    if (element instanceof TypeElement) {
      Element mappedSuper = types.asElement(((TypeElement) element).getSuperclass());
      if (mappedSuper != null && !"java.lang.Object".equals(mappedSuper.toString())) {
        gatherProperties(fields, mappedSuper);
      }
      for (VariableElement field : ElementFilter.fieldsIn(element.getEnclosedElements())) {
        if (!ignoreField(field)) {
          fields.add(field);
        }
      }
    }
  }

  /**
   * Ignore static or transient fields.
   */
  private boolean ignoreField(VariableElement field) {
    return isStaticOrTransient(field) || isHiddenField(field);
  }

  private boolean isHiddenField(VariableElement field) {

    Hidden hidden = field.getAnnotation(Hidden.class);
    if (hidden != null) {
      return true;
    }
    for (AnnotationMirror annotationMirror : field.getAnnotationMirrors()) {
      String simpleName = annotationMirror.getAnnotationType().asElement().getSimpleName().toString();
      if ("JsonIgnore".equals(simpleName)) {
        return true;
      }
    }
    return false;
  }

  private boolean isStaticOrTransient(VariableElement field) {
    Set<Modifier> modifiers = field.getModifiers();
    return (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.TRANSIENT));
  }

}
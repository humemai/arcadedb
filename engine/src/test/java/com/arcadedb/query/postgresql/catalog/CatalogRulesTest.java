/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.query.postgresql.catalog;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.postgresql.PostgreSQLAmbiguousIdentifierException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedColumnException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.arcadedb.query.postgresql.catalog.RelationalCatalogTest.names;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules of the relational view that need a schema of their own: the name of a property {@code id}, undeclared properties,
 * nullability, keys, the case of names, inheritance, declared edge endpoints, and the storage of edges.
 */
class CatalogRulesTest extends TestHelper {

  private Schema schema() {
    return database.getSchema();
  }

  private Table table(final String name) {
    return RelationalCatalog.of(database).table(Identifier.quoted(name));
  }

  // ---- id, undeclared properties, nullability ----

  @Test
  void aDeclaredPropertyNamedIdIsTheIdColumnAndTheRecordIdIsRid() {
    final DocumentType thing = schema().createDocumentType("thing");
    thing.createProperty("id", Type.STRING);
    thing.createProperty("name", Type.STRING);
    database.transaction(() -> database.newDocument("thing").set("id", "mine").set("name", "n").save());

    final Table table = table("thing");
    assertThat(names(table)).containsExactly("rid", "id", "name");
    assertThat(table.column("rid").kind()).isEqualTo(Column.Kind.IDENTITY);
    assertThat(table.column("rid").type()).isEqualTo(ColumnType.TEXT);
    assertThat(table.column("id").kind()).isEqualTo(Column.Kind.PROPERTY);
    assertThat(table.column("id").property()).isEqualTo("id");
    assertThat(table.identityColumn().name()).isEqualTo("rid");

    final Document row = database.<Document>iterateType("thing", false).next();
    assertThat(table.column("rid").read(row)).isEqualTo(row.getIdentity().toString());
    assertThat(table.column("id").read(row)).isEqualTo("mine");
  }

  @Test
  void aTypeWithoutAPropertyIdHasNoColumnIdAndItsRecordIdIsRid() {
    schema().createDocumentType("thing").createProperty("name", Type.STRING);

    final Table table = table("thing");
    assertThat(names(table)).containsExactly("rid", "name");
    assertThat(table.identityColumn().name()).isEqualTo("rid");
    assertThat(table.findColumn(Identifier.unquoted("id"))).isNull();
    assertThatThrownBy(() -> table.column("ID")).isInstanceOfSatisfying(PostgreSQLUndefinedColumnException.class, e -> assertThat(
        e.getHint()).isEqualTo(com.arcadedb.query.postgresql.PostgreSQLCatalogException.NO_ID_HINT));
    assertThat(table.primaryKey().columns()).containsExactly("rid");
    assertThat(table.uniqueKeys()).isEmpty();
  }

  @Test
  void thePropertyIdIsThePrimaryKeyWhenAUniqueIndexCoversItAndTheRecordIdIsAUniqueKey() {
    final DocumentType person = schema().createVertexType("person");
    person.createProperty("id", Type.LONG);
    person.createProperty("name", Type.STRING);
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "person", "id");

    final Table table = table("person");
    assertThat(names(table)).containsExactly("rid", "id", "name");
    assertThat(table.primaryKey().name()).isEqualTo("person_pkey");
    assertThat(table.primaryKey().columns()).containsExactly("id");
    assertThat(table.uniqueKeys()).extracting(Table.Key::name).containsExactly("person_rid_key");
    assertThat(table.uniqueKeys().get(0).columns()).containsExactly("rid");
    assertThat(table.keyOn(List.of("rid")).name()).isEqualTo("person_rid_key");
    assertThat(table.keyOn(List.of("name"))).isNull();
    // a primary key is NOT NULL, as in PostgreSQL; the record id is NOT NULL too
    assertThat(table.column("id").nullable()).isFalse();
    assertThat(table.column("rid").nullable()).isFalse();
    assertThat(table.column("name").nullable()).isTrue();
  }

  @Test
  void thePropertyIdWithoutAUniqueIndexAloneLeavesTheRecordIdAsThePrimaryKey() {
    final DocumentType thing = schema().createDocumentType("thing");
    thing.createProperty("id", Type.LONG);
    thing.createProperty("kind", Type.STRING);
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "thing", "id");
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "thing", "id", "kind");

    final Table table = table("thing");
    assertThat(table.primaryKey().columns()).containsExactly("rid");
    // a unique index over two columns is a unique key and does not make id the primary key
    assertThat(table.uniqueKeys()).extracting(Table.Key::columns).containsExactly(List.of("id", "kind"));
    assertThat(table.column("id").nullable()).isTrue();
  }

  @Test
  void aPropertyRidWinsItsNameAndTheRecordIdHasNoColumn() {
    final DocumentType badge = schema().createDocumentType("badge");
    badge.createProperty("rid", Type.STRING);
    badge.createProperty("color", Type.STRING);

    final Table table = table("badge");
    assertThat(names(table)).containsExactly("color", "rid");
    assertThat(table.identityColumn()).isNull();
    assertThat(table.column("rid").kind()).isEqualTo(Column.Kind.PROPERTY);
    assertThat(table.primaryKey()).isNull();
  }

  @Test
  void aTypeWithBothPropertiesIdAndRidHasNoRecordIdColumnAndNoForeignKeyPointsAtIt() {
    final DocumentType both = schema().createVertexType("both");
    both.createProperty("id", Type.LONG);
    both.createProperty("rid", Type.STRING);
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "both", "id");
    final EdgeType link = schema().createEdgeType("link");
    link.setCustomValue(EdgeEndpoints.SOURCE_KEY, "both");
    link.setCustomValue(EdgeEndpoints.TARGET_KEY, "both");
    final DocumentType note = schema().createDocumentType("note");
    note.createProperty("about", Type.LINK, "both");

    final Table table = table("both");
    assertThat(names(table)).containsExactly("id", "rid");
    assertThat(table.identityColumn()).isNull();
    assertThat(table.column("id").kind()).isEqualTo(Column.Kind.PROPERTY);
    assertThat(table.column("rid").kind()).isEqualTo(Column.Kind.PROPERTY);
    assertThat(table.primaryKey().columns()).containsExactly("id");
    assertThat(table.uniqueKeys()).isEmpty();
    // the edge columns still hold record ids, and nothing declares what they refer to
    assertThat(names(table("link"))).containsExactly("rid", "source_id", "target_id");
    assertThat(table("link").foreignKeys()).isEmpty();
    assertThat(table("link").sourceTable()).isNull();
    assertThat(table("note").foreignKeys()).isEmpty();
  }

  @Test
  void theForeignKeysOfEdgesAndLinksPointAtTheRecordIdColumnOfATypeWithItsOwnId() {
    final DocumentType person = schema().createVertexType("person");
    person.createProperty("id", Type.LONG);
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "person", "id");
    final DocumentType tag = schema().createVertexType("tag");
    tag.createProperty("name", Type.STRING);
    final EdgeType likes = schema().createEdgeType("likes");
    likes.setCustomValue(EdgeEndpoints.SOURCE_KEY, "person");
    likes.setCustomValue(EdgeEndpoints.TARGET_KEY, "tag");
    final DocumentType post = schema().createDocumentType("post");
    post.createProperty("author", Type.LINK, "person");
    post.createProperty("topic", Type.LINK, "tag");

    final Table likesTable = table("likes");
    assertThat(references(likesTable)).containsExactly("likes_source_id_fkey -> person.rid", "likes_target_id_fkey -> tag.rid");
    assertThat(references(table("post"))).containsExactly("post_author_fkey -> person.rid", "post_topic_fkey -> tag.rid");

    // the values of source_id and target_id are record ids, whatever the endpoint types call their key
    final String[] ids = new String[2];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("person").set("id", 7L).save();
      final MutableVertex b = database.newVertex("tag").set("name", "x").save();
      ids[0] = a.getIdentity().toString();
      ids[1] = b.getIdentity().toString();
      a.newEdge("likes", b);
    });
    final Edge edge = database.<Edge>iterateType("likes", false).next();
    assertThat(likesTable.column("source_id").read(edge)).isEqualTo(ids[0]);
    assertThat(likesTable.column("target_id").read(edge)).isEqualTo(ids[1]);
    final Document personRow = database.<Document>iterateType("person", false).next();
    assertThat(table("person").column("rid").read(personRow)).isEqualTo(ids[0]);
    assertThat(table("person").column("id").read(personRow)).isEqualTo(7L);
  }

  private static List<String> references(final Table table) {
    final List<String> out = new ArrayList<>();
    for (final Table.ForeignKey foreignKey : table.foreignKeys())
      out.add(foreignKey.name() + " -> " + foreignKey.referencedTable() + "." + String.join(",", foreignKey.referencedColumns()));
    return out;
  }

  @Test
  void anEdgeTypeWithAPropertyIdHasThePropertyAsIdAndTheRecordIdAsRid() {
    final EdgeType follows = schema().createEdgeType("follows");
    follows.createProperty("id", Type.INTEGER);
    follows.createProperty("since", Type.INTEGER);

    final Table table = table("follows");
    assertThat(names(table)).containsExactly("rid", "source_id", "target_id", "id", "since");
    assertThat(table.column("rid").kind()).isEqualTo(Column.Kind.IDENTITY);
    assertThat(table.column("id").property()).isEqualTo("id");
    assertThat(table.primaryKey().columns()).containsExactly("rid");
  }

  @Test
  void aFurtherUnderscoreIsAddedToAnEndpointNameWhenItsUnderscoreFormExistsToo() {
    final EdgeType links = schema().createEdgeType("links");
    links.createProperty("source_id", Type.STRING);
    links.createProperty("source_id_", Type.INTEGER);

    final Table table = table("links");
    assertThat(names(table)).containsExactly("rid", "source_id", "target_id", "source_id__", "source_id_");
    assertThat(table.column("source_id__").property()).isEqualTo("source_id");
    assertThat(table.column("source_id_").property()).isEqualTo("source_id_");
    assertThat(table.column("source_id_").type()).isEqualTo(ColumnType.INT4);
  }

  @Test
  void theEndpointColumnsOfAnEdgeTableAreRenamedTheSameWay() {
    final EdgeType links = schema().createEdgeType("links");
    links.createProperty("source_id", Type.STRING);

    final Table table = table("links");
    assertThat(names(table)).containsExactly("rid", "source_id", "target_id", "source_id_");
    assertThat(table.column("source_id").kind()).isEqualTo(Column.Kind.SOURCE);
    assertThat(table.column("source_id_").property()).isEqualTo("source_id");
  }

  @Test
  void undeclaredPropertiesAreNotColumnsAndAnUnknownColumnIs42703WithAHint() {
    schema().createDocumentType("person").createProperty("name", Type.STRING);
    database.transaction(() -> {
      database.newDocument("person").set("name", "Ada").set("nickname", "A").save();
      database.newDocument("person").save();
    });

    final Table person = table("person");
    assertThat(names(person)).containsExactly("rid", "name");
    assertThat(person.findColumn(Identifier.unquoted("nickname"))).isNull();
    assertThatThrownBy(() -> person.column("nickname"))
        .isInstanceOfSatisfying(PostgreSQLUndefinedColumnException.class, e -> {
          assertThat(e.getSqlState()).isEqualTo("42703");
          // no column is close to the name, and the hint does not say to add one (fork issue #220)
          assertThat(e.getHint()).isEqualTo("the columns of person are rid, name");
        })
        .hasMessageContaining("column \"nickname\" of relation \"person\" does not exist")
        .hasMessageContaining("Hint: ");
  }

  @Test
  void anAbsentPropertyReadsAsNull() {
    schema().createDocumentType("person").createProperty("name", Type.STRING);
    database.transaction(() -> {
      database.newDocument("person").set("name", "Ada").save();
      database.newDocument("person").save();
    });

    final Column name = table("person").column("name");
    final List<Object> values = new ArrayList<>();
    final Iterator<Document> rows = database.iterateType("person", false);
    while (rows.hasNext())
      values.add(name.read(rows.next()));
    assertThat(values).containsExactlyInAnyOrder("Ada", null);
  }

  @Test
  void aPropertyIsNullableUnlessItIsNotNullAndTheSyntheticColumnsNever() {
    final DocumentType person = schema().createVertexType("person");
    person.createProperty("name", Type.STRING).setNotNull(true);
    person.createProperty("age", Type.INTEGER);

    final Table table = table("person");
    assertThat(table.column("name").nullable()).isFalse();
    assertThat(table.column("age").nullable()).isTrue();
    assertThat(table.column("rid").nullable()).isFalse();
    assertThat(table.column("rid").isSynthetic()).isTrue();
    assertThat(table.column("age").isSynthetic()).isFalse();
  }

  // ---- keys ----

  @Test
  void uniqueKeysAreTheUniqueKeyIndexesOnDeclaredPropertiesAndNotTheOthers() {
    final DocumentType person = schema().createVertexType("person");
    person.createProperty("pid", Type.INTEGER);
    person.createProperty("name", Type.STRING);
    person.createProperty("city", Type.STRING);
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "person", "pid");
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "person", "name", "city");
    schema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "person", "city");

    final Table table = table("person");
    assertThat(table.uniqueKeys()).extracting(Table.Key::name).containsExactly("person_name_city_key", "person_pid_key");
    assertThat(table.uniqueKeys().get(0).columns()).containsExactly("name", "city");
    assertThat(table.uniqueKeys().get(1).columns()).containsExactly("pid");
    assertThat(table.primaryKey().columns()).containsExactly("rid");
  }

  @Test
  void aUniqueRegularEdgeTypeHasAUniqueKeyOnItsEndpointsAndNoMetadataColumn() {
    schema().createVertexType("person");
    schema().buildEdgeType().withName("follows").withUnique(true).create();

    final Table table = table("follows");
    assertThat(names(table)).containsExactly("rid", "source_id", "target_id");
    assertThat(table.primaryKey().columns()).containsExactly("rid");
    assertThat(table.uniqueKeys()).extracting(Table.Key::columns).containsExactly(List.of("source_id", "target_id"));
    assertThat(table.uniqueKeys().get(0).name()).isEqualTo("follows_source_id_target_id_key");
  }

  @Test
  void aLinkIsAForeignKeyOnlyWhenItNamesATableAndAnUntypedLinkIsAColumnOnly() {
    schema().createVertexType("product");
    final DocumentType line = schema().createDocumentType("line");
    line.createProperty("product", Type.LINK, "product");
    line.createProperty("loose", Type.LINK);

    final Table table = table("line");
    assertThat(table.foreignKeys()).hasSize(1);
    final Table.ForeignKey foreignKey = table.foreignKeys().get(0);
    assertThat(foreignKey.name()).isEqualTo("line_product_fkey");
    assertThat(foreignKey.columns()).containsExactly("product");
    assertThat(foreignKey.referencedTable()).isEqualTo("product");
    assertThat(foreignKey.referencedColumns()).containsExactly("rid");
    assertThat(table.column("loose").type()).isEqualTo(ColumnType.TEXT);
    assertThat(table.column("loose").isLink()).isTrue();

    // the value of a link is the record id of its target as text
    final String[] target = new String[1];
    database.transaction(() -> {
      final MutableVertex product = database.newVertex("product").save();
      target[0] = product.getIdentity().toString();
      database.newDocument("line").set("product", product).save();
    });
    final Document row = database.<Document>iterateType("line", false).next();
    assertThat(table.column("product").read(row)).isEqualTo(target[0]);
    assertThat(table.column("loose").read(row)).isNull();
  }

  // ---- the case of names ----

  @Test
  void anUnquotedNameIsFoldedMatchedExactlyAndThenByTheOneNameThatDiffersInCase() {
    schema().createDocumentType("Item");
    schema().createDocumentType("Tab");
    schema().createDocumentType("tab");
    final RelationalCatalog catalog = RelationalCatalog.of(database);

    assertThat(catalog.table("item").name()).isEqualTo("Item");
    assertThat(catalog.table("ITEM").name()).isEqualTo("Item");
    assertThat(catalog.table("Item").name()).isEqualTo("Item");
    assertThat(catalog.table(Identifier.quoted("Item")).name()).isEqualTo("Item");

    // two types that differ only in case: the folded name matches exactly one of them, so there is no ambiguity
    assertThat(catalog.table("tab").name()).isEqualTo("tab");
    assertThat(catalog.table("TAB").name()).isEqualTo("tab");
    assertThat(catalog.table(Identifier.quoted("Tab")).name()).isEqualTo("Tab");
  }

  @Test
  void aQuotedNameMatchesExactlyAndNothingElse() {
    schema().createDocumentType("Item");
    final RelationalCatalog catalog = RelationalCatalog.of(database);

    assertThat(catalog.findTable(Identifier.quoted("item"))).isNull();
    assertThat(catalog.findTable(Identifier.quoted("ITEM"))).isNull();
    assertThatThrownBy(() -> catalog.table(Identifier.quoted("item")))
        .isInstanceOfSatisfying(PostgreSQLUndefinedTableException.class, e -> assertThat(e.getSqlState()).isEqualTo("42P01"))
        .hasMessage("relation \"item\" does not exist");
  }

  @Test
  void twoTablesThatDifferOnlyInCaseMakeAnUnquotedNameAmbiguous() {
    schema().createDocumentType("Thing");
    schema().createDocumentType("THING");
    final RelationalCatalog catalog = RelationalCatalog.of(database);

    assertThatThrownBy(() -> catalog.table("thing"))
        .isInstanceOfSatisfying(PostgreSQLAmbiguousIdentifierException.class, e -> {
          assertThat(e.getSqlState()).isEqualTo("42P09");
          assertThat(e.getCandidates()).containsExactly("THING", "Thing");
        })
        .hasMessageContaining("\"THING\", \"Thing\"");
    assertThat(catalog.table(Identifier.quoted("Thing")).name()).isEqualTo("Thing");
    assertThat(catalog.table(Identifier.quoted("THING")).name()).isEqualTo("THING");
  }

  @Test
  void columnsFollowTheSameRules() {
    final DocumentType wide = schema().createDocumentType("wide");
    wide.createProperty("Name", Type.STRING);
    wide.createProperty("NAME", Type.STRING);
    wide.createProperty("City", Type.STRING);
    final Table table = table("wide");

    assertThat(table.column("city").name()).isEqualTo("City");
    assertThat(table.column("CITY").name()).isEqualTo("City");
    assertThat(table.column(Identifier.quoted("Name")).name()).isEqualTo("Name");
    assertThat(table.findColumn(Identifier.quoted("name"))).isNull();
    assertThatThrownBy(() -> table.column("name"))
        .isInstanceOfSatisfying(PostgreSQLAmbiguousIdentifierException.class, e -> {
          assertThat(e.getSqlState()).isEqualTo("42702");
          assertThat(e.getCandidates()).containsExactly("NAME", "Name");
        });
  }

  // ---- inheritance ----

  @Test
  void aSupertypeTableHasTheRowsOfItsSubtypesAndTheColumnsOfItsOwn() {
    schema().createVertexType("animal").createProperty("name", Type.STRING);
    schema().buildVertexType().withName("dog").withSuperType("animal").create().createProperty("breed", Type.STRING);
    schema().buildVertexType().withName("puppy").withSuperType("dog").create().createProperty("toy", Type.STRING);
    database.transaction(() -> {
      database.newVertex("animal").set("name", "a").save();
      database.newVertex("dog").set("name", "d").save();
      database.newVertex("puppy").set("name", "p").save();
    });
    final RelationalCatalog catalog = RelationalCatalog.of(database);
    final Table animal = catalog.table("animal");
    final Table dog = catalog.table("dog");
    final Table puppy = catalog.table("puppy");

    assertThat(animal.superTables()).isEmpty();
    assertThat(animal.subTables()).containsExactly("dog");
    assertThat(dog.superTables()).containsExactly("animal");
    assertThat(dog.subTables()).containsExactly("puppy");
    assertThat(puppy.superTables()).containsExactly("dog");
    assertThat(puppy.subTables()).isEmpty();

    // a subtype has the columns of its supertypes first, a supertype only its own
    assertThat(names(animal)).containsExactly("rid", "name");
    assertThat(names(dog)).containsExactly("rid", "name", "breed");
    assertThat(names(puppy)).containsExactly("rid", "name", "breed", "toy");

    // the premise of the rule: a polymorphic read of the type is the table, FROM ONLY is the type's own buckets
    assertThat(database.countType("animal", true)).isEqualTo(3);
    assertThat(database.countType("animal", false)).isEqualTo(1);
    assertThat(animal.documentType().getBucketIds(true).size()).isGreaterThan(animal.documentType().getBucketIds(false).size());
    assertThat(animal.documentType().getBucketIds(true).size())
        .isEqualTo(animal.documentType().getBucketIds(false).size() + dog.documentType().getBucketIds(false).size()
            + puppy.documentType().getBucketIds(false).size());
  }

  // ---- declared edge endpoints ----

  @Test
  void anEdgeTypeWithNoDeclaredEndpointsHasNoForeignKeyButIsStillATable() {
    schema().createVertexType("person");
    schema().createEdgeType("likes");

    final Table likes = table("likes");
    assertThat(names(likes)).containsExactly("rid", "source_id", "target_id");
    assertThat(likes.foreignKeys()).isEmpty();
    assertThat(likes.sourceTable()).isNull();
    assertThat(likes.targetTable()).isNull();
  }

  @Test
  void mixedEndpointPairsAreDeclaredAsTheirCommonSupertype() {
    schema().createVertexType("person");
    schema().createVertexType("item");
    schema().buildVertexType().withName("book").withSuperType("item").create();
    schema().buildVertexType().withName("toy").withSuperType("item").create();
    final EdgeType owns = schema().createEdgeType("owns");
    owns.setCustomValue(EdgeEndpoints.SOURCE_KEY, "person");
    owns.setCustomValue(EdgeEndpoints.TARGET_KEY, "item");

    final Table table = table("owns");
    assertThat(table.foreignKeys()).extracting(Table.ForeignKey::referencedTable).containsExactly("person", "item");
    assertThat(table.sourceTable()).isEqualTo("person");
    assertThat(table.targetTable()).isEqualTo("item");
    assertThat(table("item").subTables()).containsExactly("book", "toy");
  }

  @Test
  void aDeclarationThatNamesNoVertexTableIsNotAForeignKey() {
    schema().createVertexType("person");
    schema().createDocumentType("doc");
    final EdgeType ghosts = schema().createEdgeType("ghosts");
    ghosts.setCustomValue(EdgeEndpoints.SOURCE_KEY, "ghost");
    ghosts.setCustomValue(EdgeEndpoints.TARGET_KEY, "doc");
    final EdgeType half = schema().createEdgeType("half");
    half.setCustomValue(EdgeEndpoints.SOURCE_KEY, "person");

    assertThat(table("ghosts").foreignKeys()).isEmpty();
    assertThat(table("half").foreignKeys()).extracting(Table.ForeignKey::columns).containsExactly(List.of("source_id"));
    assertThat(table("half").targetTable()).isNull();
  }

  @Test
  void aDeclarationFindsTheTypeIgnoringCaseWhenThereIsOneMatchAndNeverGuessesOtherwise() {
    schema().createVertexType("Person");
    schema().createVertexType("Thing");
    schema().createVertexType("THING");
    final EdgeType edge = schema().createEdgeType("e");
    edge.setCustomValue(EdgeEndpoints.SOURCE_KEY, "PERSON");
    edge.setCustomValue(EdgeEndpoints.TARGET_KEY, "thing");

    final Table table = table("e");
    assertThat(table.sourceTable()).isEqualTo("Person");
    assertThat(table.targetTable()).as("two types match, nothing is guessed").isNull();
  }

  // ---- edge storage ----

  @Test
  void aBidirectionalAndAUnidirectionalEdgeTypeAreOneRowPerEdgeRecord() {
    schema().createVertexType("person");
    schema().buildEdgeType().withName("bi").withBidirectional(true).create();
    schema().buildEdgeType().withName("uni").withBidirectional(false).create();
    final String[] ends = new String[2];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("person").save();
      final MutableVertex b = database.newVertex("person").save();
      ends[0] = a.getIdentity().toString();
      ends[1] = b.getIdentity().toString();
      a.newEdge("bi", b);
      a.newEdge("uni", b);
    });

    for (final String type : List.of("bi", "uni")) {
      final Table table = table(type);
      int rows = 0;
      final Iterator<Document> iterator = database.iterateType(type, false);
      while (iterator.hasNext()) {
        final Document edge = iterator.next();
        assertThat(table.column("source_id").read(edge)).as(type).isEqualTo(ends[0]);
        assertThat(table.column("target_id").read(edge)).as(type).isEqualTo(ends[1]);
        assertThat(table.column("rid").read(edge)).isEqualTo(edge.getIdentity().toString());
        ++rows;
      }
      assertThat(rows).as("one row per edge record of " + type).isEqualTo(1);
      assertThat(table.edgeStorage()).isEqualTo(Table.EdgeStorage.REGULAR);
      assertThat(table.edgeScan()).isEqualTo(Table.EdgeScan.BUCKETS);
      assertThat(table.walkFromTables()).isEmpty();
    }
  }

  @Test
  void aLightweightEdgeTableHasNoIdAndAPrimaryKeyOnItsEndpointsWhenUnique() {
    schema().createVertexType("person");
    schema().createVertexType("company");
    final EdgeType knows = schema().buildEdgeType().withName("knows").withLightweight(true).withUnique(true).create();
    knows.setCustomValue(EdgeEndpoints.SOURCE_KEY, "person");
    knows.setCustomValue(EdgeEndpoints.TARGET_KEY, "company");
    schema().buildEdgeType().withName("seen").withLightweight(true).create();
    final String[] ends = new String[2];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("person").save();
      final MutableVertex b = database.newVertex("company").save();
      ends[0] = a.getIdentity().toString();
      ends[1] = b.getIdentity().toString();
      a.newEdge("knows", b);
    });

    final Table table = table("knows");
    assertThat(names(table)).containsExactly("source_id", "target_id");
    assertThat(table.findColumn(Identifier.unquoted("rid"))).isNull();
    assertThat(table.primaryKey().name()).isEqualTo("knows_pkey");
    assertThat(table.primaryKey().columns()).containsExactly("source_id", "target_id");
    assertThat(table.uniqueKeys()).isEmpty();
    assertThat(table.foreignKeys()).extracting(Table.ForeignKey::referencedTable).containsExactly("person", "company");
    assertThat(table.edgeStorage()).isEqualTo(Table.EdgeStorage.LIGHTWEIGHT);
    // a bucket scan would miss them: the scan layer walks the declared source table
    assertThat(table.edgeScan()).isEqualTo(Table.EdgeScan.VERTEX_WALK);
    assertThat(table.walkFromTables()).containsExactly("person");

    // not unique: no primary key, and with no declared source the walk starts at every vertex table without a supertype
    final Table seen = table("seen");
    assertThat(seen.primaryKey()).isNull();
    assertThat(seen.foreignKeys()).isEmpty();
    assertThat(seen.edgeScan()).isEqualTo(Table.EdgeScan.VERTEX_WALK);
    assertThat(seen.walkFromTables()).containsExactly("company", "person");

    // the endpoints are read from the edge, which has no record
    final Vertex a = database.<Vertex>iterateType("person", false).next();
    final Iterator<Edge> edges = a.getEdges(Vertex.DIRECTION.OUT, "knows").iterator();
    final Edge edge = edges.next();
    assertThat(edges.hasNext()).isFalse();
    assertThat(table.column("source_id").read(edge)).isEqualTo(ends[0]);
    assertThat(table.column("target_id").read(edge)).isEqualTo(ends[1]);
  }

  @Test
  void aTypeThatHoldsRegularAndLightweightEdgesHasANullableIdAndNoPrimaryKey() {
    schema().createVertexType("person");
    schema().createEdgeType("base_e");
    schema().buildEdgeType().withName("sub_e").withSuperType("base_e").withLightweight(true).create();
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("person").save();
      final MutableVertex b = database.newVertex("person").save();
      a.newEdge("base_e", b);
      a.newEdge("sub_e", b);
    });

    final RelationalCatalog catalog = RelationalCatalog.of(database);
    final Table base = catalog.table("base_e");
    assertThat(base.edgeStorage()).isEqualTo(Table.EdgeStorage.MIXED);
    assertThat(names(base)).containsExactly("rid", "source_id", "target_id");
    assertThat(base.column("rid").nullable()).isTrue();
    assertThat(base.primaryKey()).isNull();
    assertThat(base.edgeScan()).isEqualTo(Table.EdgeScan.VERTEX_WALK);
    assertThat(base.subTables()).containsExactly("sub_e");

    final Table sub = catalog.table("sub_e");
    assertThat(sub.edgeStorage()).isEqualTo(Table.EdgeStorage.LIGHTWEIGHT);
    assertThat(names(sub)).containsExactly("source_id", "target_id");
    assertThat(sub.superTables()).containsExactly("base_e");

    // the edge that has a record has an id, the lightweight one has none
    final Vertex a = database.<Vertex>iterateType("person", false).next();
    final List<Object> ids = new ArrayList<>();
    for (final Edge edge : a.getEdges(Vertex.DIRECTION.OUT))
      ids.add(base.column("rid").read(edge));
    assertThat(ids).hasSize(2);
    assertThat(ids).containsNull();
    assertThat(ids).filteredOn(id -> id != null).hasSize(1);
  }
}

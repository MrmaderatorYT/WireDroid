package com.ccs.wiredroid.uml;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class UmlGraphTest {

    @Test
    public void testFromPlantUmlBasic() {
        String puml = "@startuml\n"
                + "class Animal {\n"
                + "  - age: int\n"
                + "  + speak(): void\n"
                + "}\n"
                + "class Dog extends Animal {\n"
                + "  + bark(): void\n"
                + "}\n"
                + "interface Flyable {\n"
                + "  + fly(): void\n"
                + "}\n"
                + "class Bird extends Animal implements Flyable {\n"
                + "}\n"
                + "@enduml";

        UmlGraph g = UmlGraph.fromPlantUml(puml);
        assertNotNull(g);
        assertEquals(4, g.types().size());

        UmlGraph.Type animal = null, dog = null, flyable = null, bird = null;
        for (UmlGraph.Type t : g.types()) {
            if ("Animal".equals(t.name)) animal = t;
            if ("Dog".equals(t.name)) dog = t;
            if ("Flyable".equals(t.name)) flyable = t;
            if ("Bird".equals(t.name)) bird = t;
        }

        assertNotNull(animal);
        assertNotNull(dog);
        assertNotNull(flyable);
        assertNotNull(bird);

        assertEquals(2, animal.members.size());
        assertEquals(UmlGraph.Kind.CLASS, animal.kind);
        assertEquals(UmlGraph.Kind.INTERFACE, flyable.kind);

        List<UmlGraph.Relation> rels = g.relations();
        assertTrue(rels.size() >= 3);
    }

    @Test
    public void testFromPlantUmlRelations() {
        String puml = "@startuml\n"
                + "User \"1\" --> \"*\" Order\n"
                + "Dog --|> Animal\n"
                + "Bird ..|> Flyable\n"
                + "@enduml";

        UmlGraph g = UmlGraph.fromPlantUml(puml);
        assertNotNull(g);
        assertEquals(6, g.types().size());
        assertEquals(3, g.relations().size());
    }
}

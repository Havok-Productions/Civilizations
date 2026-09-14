package dev.civilizations.world;

import dev.civilizations.core.CraftingBook;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.*;

/** Captured at enable on the global thread. Exact metadata/remainder recipes are not automated. */
public final class ServerRecipes {
  private ServerRecipes() {}

  public static CraftingBook capture() {
    List<CraftingBook.Recipe> result = new ArrayList<>();
    Bukkit.recipeIterator()
        .forEachRemaining(
            recipe -> {
              if (!(recipe instanceof Keyed keyed) || recipe.getResult().hasItemMeta()) return;
              List<RecipeChoice> slots = new ArrayList<>();
              boolean furnace = recipe instanceof FurnaceRecipe;
              boolean table;
              if (recipe instanceof FurnaceRecipe cooking) {
                slots.add(cooking.getInputChoice());
                table = false;
              } else if (recipe instanceof ShapedRecipe shaped) {
                String[] shape = shaped.getShape();
                table = shape.length > 2 || Arrays.stream(shape).anyMatch(row -> row.length() > 2);
                for (String row : shape)
                  for (char c : row.toCharArray()) {
                    RecipeChoice choice = shaped.getChoiceMap().get(c);
                    if (choice != null) slots.add(choice);
                  }
              } else if (recipe instanceof ShapelessRecipe shapeless) {
                slots.addAll(shapeless.getChoiceList());
                table = slots.size() > 4;
              } else return;
              List<List<String>> choices = new ArrayList<>();
              for (RecipeChoice choice : slots) {
                if (!(choice instanceof RecipeChoice.MaterialChoice materials)) return;
                List<String> names =
                    materials.getChoices().stream()
                        .filter(m -> !m.name().contains("BUCKET") && !m.name().contains("BOTTLE"))
                        .map(Enum::name)
                        .toList();
                if (names.isEmpty()) return;
                choices.add(names);
              }
              if (!choices.isEmpty())
                result.add(
                    new CraftingBook.Recipe(
                        keyed.getKey().toString(),
                        recipe.getResult().getType().name(),
                        recipe.getResult().getAmount(),
                        choices,
                        table,
                        furnace));
            });
    return new CraftingBook(result);
  }
}

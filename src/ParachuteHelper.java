import net.sf.openrocket.database.ComponentPresetDao;
import net.sf.openrocket.preset.ComponentPreset;
import net.sf.openrocket.preset.ComponentPreset.Type;
import net.sf.openrocket.rocketcomponent.Parachute;
import net.sf.openrocket.startup.Application;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to manage parachute presets from OpenRocket's database
 */
public class ParachuteHelper {
    
    /**
     * Get all available parachute presets from OpenRocket's database
     * @return List of parachute presets
     */
    public static List<ComponentPreset> getAllParachutePresets() {
        ComponentPresetDao presetDao = Application.getComponentPresetDao();
        return presetDao.listForType(Type.PARACHUTE);
    }
    
    /**
     * Create a display name for a parachute preset
     * @param preset The parachute preset
     * @return A formatted display name with manufacturer and diameter
     */
    public static String getPresetDisplayName(ComponentPreset preset) {
        if (preset == null) return "Default Parachute";
        
        String manufacturer = preset.getManufacturer().getDisplayName();
        double diameter = preset.get(ComponentPreset.DIAMETER);
        String name = preset.getPartNo();
        
        return String.format("%s - %s (%.1f cm)", 
                manufacturer, 
                name, 
                diameter * 100); // Convert from meters to cm
    }
    
    /**
     * Apply a preset to a parachute component
     * @param chute The parachute component to modify
     * @param preset The preset to apply, or null to leave as is
     */
    public static void applyPreset(Parachute chute, ComponentPreset preset) {
        if (preset == null) return;
        chute.loadPreset(preset);
    }
    
    /**
     * Find a preset by its display name
     * @param displayName The display name to search for
     * @return The matching preset, or null if not found
     */
    public static ComponentPreset findPresetByDisplayName(String displayName) {
        if (displayName == null || displayName.equals("None")) return null;
        
        List<ComponentPreset> presets = getAllParachutePresets();
        for (ComponentPreset preset : presets) {
            if (getPresetDisplayName(preset).equals(displayName)) {
                return preset;
            }
        }
        return null;
    }
} 
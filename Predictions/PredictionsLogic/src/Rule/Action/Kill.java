package Rule.Action;

import Context.Context;
import Dto.ActionDetailsDto;
import Entity.definition.EntityDefinition;
import Entity.instance.EntityInstance;
import PRD.PRDAction;
import Property.definition.EnvPropertyDefinition;

import java.util.HashMap;

public class Kill extends Action{

    private EntityDefinition entity;

    public Kill(PRDAction prdAction, HashMap<String,EntityDefinition> entities, HashMap<String, EnvPropertyDefinition> environmentProperties) {
        super(prdAction, entities, environmentProperties);
        this.entity = entities.get(prdAction.getEntity());
    }

    @Override
    public void Activate(Context context) {
        context.removeActiveEntity();
    }

    @Override
    public ActionDetailsDto getDetails() {
        return new ActionDetailsDto("Type: " + this.type
                +"\nEntity: " + entity.getName());
    }

    @Override
    public EntityDefinition getMainEntity() {
        return entity;
    }


}

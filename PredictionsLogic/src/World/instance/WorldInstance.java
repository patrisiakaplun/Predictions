package World.instance;

import Context.Context;
import Dto.SimulationDetailsDto;
import Dto.SimulationEndDetailsDto;
import EndSimulationDetails.EntitySimulationEndDetails;
import EndSimulationDetails.PropertySimulationEndDetails;
import Entity.definition.EntityDefinition;
import Entity.instance.EntityInstance;
import Property.PropertyType;
import Property.definition.EnvPropertyDefinition;
import Property.instance.EnvPropertyInstance;
import Property.instance.PropertyInstance;
import Rule.Rule;
import Terminition.Termination;
import Terminition.TerminationType;
import UserRequest.UserRequest;
import World.definition.WorldDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

public class WorldInstance implements Runnable {

    private List<EntityInstance> entities;
    private HashMap<String, EnvPropertyInstance> environmentProperties;
    private List<Rule> rules;
    private HashMap<TerminationType,Termination> terminationConditions;
    private EntityInstance[][] grid;
    private Integer tick;
    private HashMap<String, EntitySimulationEndDetails> endSimulationDetails;
    private SimulationStatusType status;
    private final Integer SimulationId;
    private Long runningTimeInSeconds;
    private final UserRequest request;
    private SimulationEndDetailsDto endDetailsDto;

    public WorldInstance(WorldDefinition worldDef,Map<String, String> envPropValue,UserRequest request){

        if(worldDef == null)
        {
            throw new RuntimeException("There is no simulation loaded in the system");
        }

        //Get EnvProperties
        environmentProperties = new HashMap<>();
        for (Map.Entry<String, EnvPropertyDefinition> envProperty : worldDef.getEnvironmentProperties().entrySet()) {
            this.environmentProperties.put(envProperty.getKey(),new EnvPropertyInstance(envProperty.getValue()));
        }
        InitializeEnvVariablesValue(envPropValue);

        //Get grid
        grid = new EntityInstance[worldDef.getGrid().getRows()][worldDef.getGrid().getColumns()];

        //Get entities
        int entityId = 1;
        entities = new ArrayList<>();
        for (Map.Entry<String, EntityDefinition> entity : worldDef.getEntities().entrySet()) {
            for (int i = 0; i < entity.getValue().getPopulation(); i++) {
                this.entities.add(new EntityInstance(entityId,entity.getValue(),grid));
                entityId++;
            }
        }

        //Get termination
        terminationConditions = worldDef.getTerminationConditions();

        //Get rules
        rules = worldDef.getRules();

        //Initialization endSimulationDetails map
        endSimulationDetails = new HashMap<>();
        for (Map.Entry<String, EntityDefinition> entity : worldDef.getEntities().entrySet()) {
            endSimulationDetails.put(entity.getKey(),new EntitySimulationEndDetails(entity.getValue()));
        }

        //Initialization Tick
        tick = 1;

        //Initialization Status
        status = SimulationStatusType.Waiting;

        //Id
        SimulationId = hashCode();

        //request
        this.request = request;
    }

    @Override
    public void run() {
        status = SimulationStatusType.Running;

        synchronized (request) {
            request.setNumOfRunningSimulation(request.getNumOfRunningSimulation() + 1);
        }

        boolean simulationTermByTicks = terminationConditions.containsKey(TerminationType.TICK);
        boolean simulationTermBySecond = terminationConditions.containsKey(TerminationType.SECOND);
        Instant startTime = Instant.now();
        long waitTimeInSecCount = 0 ;

        while (!status.equals(SimulationStatusType.Stop)) {

            Instant startItrTime = Instant.now();
            long waitTimeInSecPause = 0;
            while(status.equals(SimulationStatusType.Pause)) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                waitTimeInSecPause = Duration.between(startItrTime, Instant.now()).getSeconds();
            }
            waitTimeInSecCount += waitTimeInSecPause;

            runningTimeInSeconds = Duration.between(startTime, Instant.now()).getSeconds() - waitTimeInSecCount;
            updateEntitiesPopulation();
            MoveEntitiesOneStepRandomly();

            for (EntityInstance entity : entities) {

                if (!entity.getAlive()) {
                    continue;
                }

                for (Rule rule : rules) {
                    if (rule.RuleIsRunnable(tick)) {
                        rule.RunRule(new Context(entity, this, tick));
                    }
                }
            }

            if (simulationTermBySecond && runningTimeInSeconds >= (long)terminationConditions.get(TerminationType.SECOND).getCount()) {
                status = SimulationStatusType.End;
                break;
            }

            if (simulationTermByTicks && tick >= this.terminationConditions.get(TerminationType.TICK).getCount()) {
                status = SimulationStatusType.End;
                break;
            }

            tick++;
        }

        getEndSimulationDetails();
        status = SimulationStatusType.End;

        synchronized (request) {
            request.setNumOfRunningSimulation(request.getNumOfRunningSimulation() - 1);
            request.setNumOfTerminateSimulations(request.getNumOfTerminateSimulations() + 1);
        }

        endDetailsDto =  new SimulationEndDetailsDto(SimulationId, request.getUsername(), request.getRequestNumber(), endSimulationDetails);
    }

    public SimulationEndDetailsDto getEndDetailsDto() {
        return endDetailsDto;
    }

    public SimulationDetailsDto getSimulationDetailsDto(){

        Map<String, Integer> entitiesPopulation = new HashMap<>();

        for (EntityInstance entityInstance: entities){
            if(entitiesPopulation.containsKey(entityInstance.getEntityDef().getName())){
                entitiesPopulation.put(entityInstance.getEntityDef().getName(),1);
            }
            else {
                entitiesPopulation.put(entityInstance.getEntityDef().getName(), entitiesPopulation.get(entityInstance.getEntityDef().getName()) + 1);
            }
        }

        return new SimulationDetailsDto(tick,runningTimeInSeconds,entitiesPopulation,status);
    }

    public void updateEntitiesPopulation() {
        for (Map.Entry<String, EntitySimulationEndDetails> entityDetails : endSimulationDetails.entrySet()) {
            entityDetails.getValue().getPopulationByTick().put(tick, 0);

            for (EntityInstance entityInstance : entities) {
                if (entityInstance.getAlive() && entityDetails.getKey().equals(entityInstance.getEntityDef().getName())) {
                    entityDetails.getValue().getPopulationByTick().put(tick, entityDetails.getValue().getPopulationByTick().get(tick) + 1);
                }
            }
            entityDetails.getValue().setCurrentPopulation(entityDetails.getValue().getPopulationByTick().get(tick));

        }
    }

    public void getEndSimulationDetails(){


        for (EntityInstance entity : entities) {

            Map<String, PropertySimulationEndDetails> entityEndSimulationPropertiesDetails = endSimulationDetails.get(entity.getEntityDef().getName()).getEndSimulationPropertiesDetails();

            if(entity.getAlive()){

                for(Map.Entry<String, PropertyInstance> property : entity.getProperties().entrySet()){

                    entityEndSimulationPropertiesDetails.get(property.getKey()).addEntitiesForAverageCalc();

                    if(entityEndSimulationPropertiesDetails.get(property.getKey()).getPropertyHistogram().containsKey(property.getValue().getValue())){
                        entityEndSimulationPropertiesDetails.get(property.getKey()).getPropertyHistogram().put(property.getValue().getValue(),
                                entityEndSimulationPropertiesDetails.get(property.getKey()).getPropertyHistogram().get(property.getValue().getValue()) + 1);
                    }

                    if(!entityEndSimulationPropertiesDetails.get(property.getKey()).getPropertyHistogram().containsKey(property.getValue().getValue())) {
                        entityEndSimulationPropertiesDetails.get(property.getKey()).getPropertyHistogram().put(property.getValue().getValue(), 1);
                    }

                    entityEndSimulationPropertiesDetails.get(property.getKey()).addToConsistency(tick.floatValue()/property.getValue().getTimesChanged());

                    if(property.getValue().getType().equals(PropertyType.FLOAT)){
                        entityEndSimulationPropertiesDetails.get(property.getKey()).addToAverage(PropertyType.FLOAT.convert(property.getValue().getValue()));
                    }

                }
            }
        }

    }

    private void MoveEntitiesOneStepRandomly() {
        for (EntityInstance entity : entities) {
            grid[entity.getCoordinate().getX()][entity.getCoordinate().getY()] = null;
            entity.getCoordinate().chooseRandomNeighbor(grid);
            grid[entity.getCoordinate().getX()][entity.getCoordinate().getY()] = entity;
        }
    }

    public void InitializeEnvVariablesValue(Map<String, String> envPropValues)
    {
        for (Map.Entry<String, EnvPropertyInstance> entry : environmentProperties.entrySet()) {
            entry.getValue().setValue(entry.getValue().getPropertyDef().getType().randomInitialization(entry.getValue().getPropertyDef().getRange()));
        }

        for (Map.Entry<String, String> envProperty : envPropValues.entrySet()) {
            PropertyType type = environmentProperties.get(envProperty.getKey()).getPropertyDef().getType();
            environmentProperties.get(envProperty.getKey()).setValue(type.convert(envProperty.getValue()));
        }
    }

    public void setStatus(SimulationStatusType status) {
        this.status = status;
    }

    public EntityInstance[][] getGrid() {
        return grid;
    }

    public Integer getGridRows()
    {
        if(grid != null){
            return grid.length;
        }
        return 0;
    }

    public EntityInstance getEntitYByPoint(Integer x,Integer y)
    {
        return grid[x][y];
    }

    public Integer getGridCols()
    {
        if(grid != null){
            return grid[0].length;
        }
        return 0;
    }

    public List<EntityInstance> getEntities() {
        return entities;
    }

    public HashMap<String, EnvPropertyInstance> getEnvironmentProperties() {
        return environmentProperties;
    }

    public Termination getTicksTermination(){
        if(terminationConditions.containsKey(TerminationType.TICK)){
            return terminationConditions.get(TerminationType.TICK);
        }
        return null;
    }

    public Termination getSecTermination(){
        if(terminationConditions.containsKey(TerminationType.SECOND)){
            return terminationConditions.get(TerminationType.SECOND);
        }
        return null;
    }

    public Integer getTick() {
        return tick;
    }

    public SimulationStatusType getStatus() {
        return status;
    }

    public long getRunningTimeInSeconds() {
        return runningTimeInSeconds;
    }

    public HashMap<String, EntitySimulationEndDetails> getSimulationDetailsMap(){
        return endSimulationDetails;
    }

    public void StopSimulation() {
        status = SimulationStatusType.Stop;
    }

    public void PauseSimulation() {
        status = SimulationStatusType.Pause;
    }

    public void ChangeSimulationStatusToRunning() {
        status = SimulationStatusType.Running;
    }

    @Override
    public String toString() {
        return "";
    }

}

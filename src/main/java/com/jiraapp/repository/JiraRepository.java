package com.jiraapp.repository;

import com.jiraapp.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;


@Repository
public class JiraRepository {


    @Autowired
    private ApplicationContext applicationContext;

    /* Gets list of all the projects in jira */
    public List<ProjectInfo> getAllProjets(){
        try
        {
            Statement statement = dbconfig.getInstance().createStatement();
            String sql="select * from project order by pname";

            ResultSet resultSet = statement.executeQuery(sql);

            List<ProjectInfo> projectsList = new ArrayList<>();

            while (resultSet.next()){

                String projectId = resultSet.getString("id");
                String projectName = resultSet.getString("pname");
                projectsList.add(new ProjectInfo(projectId,projectName));
            }

            return projectsList;
        }
        catch (SQLException sqlex){
            sqlex.printStackTrace();
        }

        return null;
    }


    /* Gets basic info about a project containing issuetype and issue Ids */
    public List<JiraIssueInfo> getJiraIssuesForProject(String projectId) throws SQLException {
        Statement statement = dbconfig.getInstance().createStatement();

        String sql = String.format("select * from jiraissue where project = %s and issuetype='10000' order by issuetype asc", projectId);

        ArrayList<JiraIssueInfo> list = new ArrayList<>();
        ResultSet resultSet = statement.executeQuery(sql);

        while (resultSet.next()) {
            String id = resultSet.getString("id");
            list.add(new JiraIssueInfo("epic", id));
            this.getIssues(id, list);
        }

        return list;
    }

    /* private function used to find all the issues under a issueId */
    private List<JiraIssueInfo> getIssues(String sourceId, List<JiraIssueInfo> list) {

        if (null == sourceId)
            return list;
        else {
            try {
                Statement statement = dbconfig.getInstance().createStatement();
                String sql = String.format("select * from jiraissue where id in (select destination from issuelink where source = %s )", sourceId);

                ResultSet resultSet = statement.executeQuery(sql);

                while (resultSet.next()) {
                    String issueId = resultSet.getString("id");
                    int issuetype = Integer.parseInt(resultSet.getString("issuetype"));

                    if (issuetype == 10001) {
                        list.add(new JiraIssueInfo("story", issueId));
                    } else if (issuetype == 10002) {
                        list.add(new JiraIssueInfo("task", issueId));
                    } else if (issuetype == 10003) {
                        list.add(new JiraIssueInfo("subtask", issueId));
                    } else {
                        list.add(new JiraIssueInfo("others", issueId));
                    }
                    this.getIssues(issueId, list);
                }

            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return list;
    }

    /* Gets detailed information of the issue */
    public JiraIssue getIssueDetails(String issueId){
        try
        {
            Statement statement = dbconfig.getInstance().createStatement();

            String sql = String.format("select ji.id, ji.project, ji.creator, ji.issuetype, ji.summary, ji.priority, ji.created, ji.timeoriginalestimate, ji.timeestimate, ji.timespent, istatus.pname as issuestatus from jiraissue as ji , issuestatus as istatus where ji.issuestatus = istatus.id and ji.id=%s",issueId);

            //String sql= String.format("select * from jiraissue where id=%s",issueId);

            ResultSet resultSet = statement.executeQuery(sql);
            JiraIssue jiraIssue = null;
            while (resultSet.next()){
                jiraIssue = applicationContext.getBean(JiraIssue.class);

                jiraIssue.setId(resultSet.getString("id"));
                jiraIssue.setProject(resultSet.getString("project"));
                jiraIssue.setCreator(resultSet.getString("creator"));
                jiraIssue.setIssuetype(resultSet.getString("issuetype"));
                jiraIssue.setSummary(resultSet.getString("summary"));
                jiraIssue.setPriority(resultSet.getString("priority"));
                jiraIssue.setCreated(resultSet.getString("created"));
                jiraIssue.setAssignedto(getAssignedTo(issueId));


                /*Fetch status for epic*/
                if(jiraIssue.getIssuetype().equalsIgnoreCase("10000")){
                    jiraIssue.setIssuestatus(this.getParentStatusFromChild(issueId));
                }else/*else get status from DB*/
                {
                    jiraIssue.setIssuestatus(resultSet.getString("issuestatus"));
                }

                /* Fetch actual start date and actual end date for issue */
                StartDateAndEndDate startDateAndEndDate = getStartAndEndDate(jiraIssue.getId(),jiraIssue.getIssuetype(),jiraIssue.getIssuestatus());

                jiraIssue.setStartdate(startDateAndEndDate.getStartdate());
                jiraIssue.setEnddate(startDateAndEndDate.getEnddate());

                /* If the issue type is Epic or story fetch total time from them */
                if(jiraIssue.getIssuetype().equalsIgnoreCase("10000") || jiraIssue.getIssuetype().equalsIgnoreCase("10001")){

                    TotalTimeCalculation totalTimeCalculation = this.calculateTotalTime(jiraIssue.getId());
                    jiraIssue.setTimeoriginalestimate(totalTimeCalculation.getTimeoriginalestimate());
                    jiraIssue.setTimeestimate(totalTimeCalculation.getTimeestimate());
                    jiraIssue.setTimespent(totalTimeCalculation.getTimespent());
                }
                else/* continue to fetch the values from DB */
                {
                    jiraIssue.setTimeoriginalestimate(resultSet.getString("timeoriginalestimate"));
                    jiraIssue.setTimeestimate(resultSet.getString("timeestimate"));
                    jiraIssue.setTimespent(resultSet.getString("timespent"));
                }
            }
            return jiraIssue;

        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
        return new JiraIssue();
    }


    /* Gets sum of timeorignalestimate and timeestimate and timespent for a epic or story */
    private TotalTimeCalculation calculateTotalTime(final String IssueId) throws SQLException {
        TotalTimeCalculation totalTimeCalculation = applicationContext.getBean(TotalTimeCalculation.class);
        List<JiraIssueInfo> issueIds = new ArrayList<>();
        this.getIssues(IssueId,issueIds);

        if(issueIds.size() > 0)
        {
            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < issueIds.size(); i++) {
                builder.append(issueIds.get(i).getIssueId());
                if (!(i == issueIds.size() - 1)) {
                    builder.append(",");
                }
            }

            String sql = String.format("select sum(timeoriginalestimate) as timeoriginalestimate, sum (timeestimate) as timeestimate , sum(timespent) as timespent from jiraissue where id in ( %s )",builder.toString());


            Statement  statement = dbconfig.getInstance().createStatement();

            ResultSet resultSet = statement.executeQuery(sql);
            resultSet.next();

            totalTimeCalculation.setTimeoriginalestimate(resultSet.getString("timeoriginalestimate"));
            totalTimeCalculation.setTimeestimate(resultSet.getString("timeestimate"));
            totalTimeCalculation.setTimespent(resultSet.getString("timespent"));
        }
        return totalTimeCalculation;
    }


    //Gets the actual startdate and actual end date for issue
    private StartDateAndEndDate getStartAndEndDate(String issueId , String issueType, String issueStatus) throws SQLException
    {

        String query = "select min(startdate ::date ) as startdate, max(startdate :: date) as enddate from worklog where issueid in ( %s )";
        Statement statement = dbconfig.getInstance().createStatement();
        StartDateAndEndDate startDateAndEndDate = applicationContext.getBean(StartDateAndEndDate.class);

        if( (!issueType.equalsIgnoreCase("10000")) && (!issueType.equalsIgnoreCase("10001")) ){
            String sql = String.format(query,issueId);


            ResultSet resultSet = statement.executeQuery(sql);
            resultSet.next();

            startDateAndEndDate.setStartdate(resultSet.getString("startdate"));
            if(issueStatus.equalsIgnoreCase("Done")){
                startDateAndEndDate.setEnddate(resultSet.getString("enddate"));
            }
        }
        else{

            List<JiraIssueInfo> issueIds = new ArrayList<>();
            this.getIssues(issueId,issueIds);
            if(issueIds.size() > 0)
            {
                StringBuilder builder = new StringBuilder();

                for (int i = 0; i < issueIds.size(); i++)
                {
                    builder.append(issueIds.get(i).getIssueId());
                    if (!(i == issueIds.size() - 1))
                    {
                        builder.append(",");
                    }
                }


                String sql = String.format(query,builder.toString());

                ResultSet resultSet = statement.executeQuery(sql);
                resultSet.next();

                startDateAndEndDate.setStartdate(resultSet.getString("startdate"));
                if(issueStatus.equalsIgnoreCase("Done")){
                    startDateAndEndDate.setEnddate(resultSet.getString("enddate"));
                }
            }
        }

        return startDateAndEndDate;
    }

    //Gets the issue assigned to
    private String getAssignedTo(String issueId) throws SQLException
    {
        String sql = String.format("select customvalue from customfieldoption where id in (select customfield from customfieldvalue where id = %s )", issueId);

        Statement statement = dbconfig.getInstance().createStatement();
        ResultSet resultSet = statement.executeQuery(sql);
        if (resultSet.next())
        {
            return resultSet.getString("customvalue");
        }

        return null;
    }


    public String getParentStatusFromChild(String issueId) throws SQLException
    {

        String query = "select pname as issuestatus from issuestatus where id in (select distinct(issuestatus) from jiraissue where id in (select destination from issuelink where source in( %s )))";

        String status="In Progress";

        List<JiraIssueInfo> jiraIssuelist = new ArrayList<>();
        this.getIssues(issueId,jiraIssuelist);

        if(jiraIssuelist.size() > 0){

            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < jiraIssuelist.size(); i++) {
                builder.append(jiraIssuelist.get(i).getIssueId());
                if (!(i == jiraIssuelist.size() - 1)) {
                    builder.append(",");
                }
            }


            String Sql = String.format(query,builder.toString());
            Statement statement = dbconfig.getInstance().createStatement();
            ResultSet resultSet = statement.executeQuery(Sql);

            if(resultSet.next()){
                if(resultSet.getString("issuestatus").equalsIgnoreCase("Done"))
                    status="Done";
            }
        }
        return status;
    }

}